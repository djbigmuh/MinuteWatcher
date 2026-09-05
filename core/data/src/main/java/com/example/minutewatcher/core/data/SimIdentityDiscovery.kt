package com.example.minutewatcher.core.data

import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.data.util.SimIdentityUtils
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.Constants.SimConstants
import com.example.minutewatcher.core.model.SimDiscoveryFailureReason
import com.example.minutewatcher.core.model.SimDiscoveryResult
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles SIM card discovery via [SubscriptionManager].
 *
 * Safe to call from any coroutine context.
 */
@Singleton
open class SimIdentityDiscovery
    @Inject
    constructor(
        private val discoveryDeps: SimDiscoveryDependencies,
    ) {
        private val context get() = discoveryDeps.context
        private val subscriptionManager get() = discoveryDeps.subscriptionManager
        private val permissionManager get() = discoveryDeps.permissionManager
        private val permissionStateNotifier get() = discoveryDeps.permissionStateNotifier

        /**
         * Observes changes in SIM subscriptions.
         * Emits whenever subscriptions might have changed.
         */
        open fun observeSubscriptionChanges(): Flow<Unit> =
            callbackFlow {
                val listener =
                    object : SubscriptionManager.OnSubscriptionsChangedListener() {
                        override fun onSubscriptionsChanged() {
                            launch { trySend(Unit) }
                        }
                    }

                var isListenerRegistered = false

                fun updateListenerRegistration() {
                    val hasPermission = permissionManager.hasPhoneStatePermission()
                    if (hasPermission && !isListenerRegistered) {
                        isListenerRegistered =
                            try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    subscriptionManager.addOnSubscriptionsChangedListener(
                                        context.mainExecutor,
                                        listener,
                                    )
                                } else {
                                    @Suppress("DEPRECATION")
                                    subscriptionManager.addOnSubscriptionsChangedListener(listener)
                                }
                                true
                            } catch (e: SecurityException) {
                                AppLogger.w("Failed to register SubscriptionsChangedListener", e)
                                false
                            }
                    } else if (!hasPermission && isListenerRegistered) {
                        try {
                            subscriptionManager.removeOnSubscriptionsChangedListener(listener)
                        } catch (e: Exception) {
                            AppLogger.w("Failed to unregister SubscriptionsChangedListener", e)
                        }
                        isListenerRegistered = false
                    }
                }

                launch {
                    permissionStateNotifier.changes.collect {
                        updateListenerRegistration()
                        trySend(Unit)
                    }
                }

                updateListenerRegistration()
                trySend(Unit)

                awaitClose {
                    if (isListenerRegistered) {
                        subscriptionManager.removeOnSubscriptionsChangedListener(listener)
                    }
                }
            }

        /**
         * Returns a list of active subscriptions for supported slots.
         */
        private fun getActiveSubscriptions(): List<SubscriptionInfo> =
            if (permissionManager.hasPhoneStatePermission()) {
                permissionManager
                    .getActiveSubscriptions()
                    .filter { it.simSlotIndex in SimConstants.SUPPORTED_SLOT_INDICES }
            } else {
                emptyList()
            }

    /**
     * Discovers active SIM cards and returns a structured result.
     */
    open fun discoverSims(seed: String): SimDiscoveryResult {
        if (!permissionManager.hasPhoneStatePermission()) {
            return SimDiscoveryResult.PermissionMissing
        }

        return try {
            val subscriptions = getActiveSubscriptions()
            val defaultDataSubId = getDefaultDataSubscriptionId()
            val defaultVoiceSubId = getDefaultVoiceSubscriptionId()

            val discoveredSims =
                subscriptions.map { info ->
                    val identity = SimIdentityUtils.createIdentityFromInfo(info, seed)
                    ActiveSimInfo(
                        identity = identity,
                        slotIndex = info.simSlotIndex,
                        subscriptionId = info.subscriptionId,
                        iccId = info.iccId,
                        displayName = info.displayName?.toString(),
                        carrierName = info.carrierName?.toString(),
                        mccMnc = SimIdentityUtils.getMccMnc(info),
                        isDefaultData = info.subscriptionId == defaultDataSubId,
                        isDefaultVoice = info.subscriptionId == defaultVoiceSubId,
                    )
                }
            SimDiscoveryResult.Available(discoveredSims)
        } catch (e: SecurityException) {
            AppLogger.w("Sim discovery failed: security restriction", e)
            SimDiscoveryResult.PermissionMissing
        } catch (e: Exception) {
            AppLogger.e("Sim discovery failed", e)
            SimDiscoveryResult.Failed(SimDiscoveryFailureReason.UNKNOWN)
        }
    }

        fun getDefaultDataSubscriptionId(): Int = permissionManager.getDefaultDataSubscriptionId()

        fun getDefaultVoiceSubscriptionId(): Int = permissionManager.getDefaultVoiceSubscriptionId()
    }
