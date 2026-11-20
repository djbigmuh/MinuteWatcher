package com.github.djbigmuh.minutewatcher.core.data

import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import com.github.djbigmuh.minutewatcher.core.common.util.AppLogger
import com.github.djbigmuh.minutewatcher.core.data.util.SimIdentityUtils
import com.github.djbigmuh.minutewatcher.core.model.ActiveSimInfo
import com.github.djbigmuh.minutewatcher.core.model.Constants.SimConstants
import com.github.djbigmuh.minutewatcher.core.model.SimDiscoveryFailureReason
import com.github.djbigmuh.minutewatcher.core.model.SimDiscoveryResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles SIM card discovery via [SubscriptionManager].
 *
 * Safe to call from any coroutine context.
 */
@Singleton
class SimIdentityDiscovery
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
        fun observeSubscriptionChanges(): Flow<Unit> =
            callbackFlow {
                val manager = subscriptionManager
                if (manager == null) {
                    trySend(Unit)
                    close()
                    awaitClose { }
                    return@callbackFlow
                }

                val listener =
                    object : SubscriptionManager.OnSubscriptionsChangedListener() {
                        override fun onSubscriptionsChanged() {
                            trySend(Unit)
                        }
                    }

                var isListenerRegistered = false

                fun updateListenerRegistration() {
                    val hasPermission = permissionManager.hasPhoneStatePermission()
                    if (hasPermission && !isListenerRegistered) {
                        isListenerRegistered =
                            try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    manager.addOnSubscriptionsChangedListener(
                                        context.mainExecutor,
                                        listener,
                                    )
                                } else {
                                    @Suppress("DEPRECATION") // Legacy pre-R API
                                    manager.addOnSubscriptionsChangedListener(listener)
                                }
                                true
                            } catch (e: SecurityException) {
                                AppLogger.w("Failed to register SubscriptionsChangedListener", e)
                                false
                            }
                    } else if (!hasPermission && isListenerRegistered) {
                        @Suppress("TooGenericExceptionCaught")
                        try {
                            manager.removeOnSubscriptionsChangedListener(listener)
                        } catch (e: Exception) {
                            // Operation must complete without propagating exception for subscription listener cleanup.
                            AppLogger.w("Failed to unregister SubscriptionsChangedListener", e)
                        }
                        isListenerRegistered = false
                    }
                }

                // All registration logic is confined to a single coroutine to avoid races
                // on the non-thread-safe isListenerRegistered flag.
                launch {
                    permissionStateNotifier.changes
                        .onStart { emit(Unit) }
                        .collect {
                            updateListenerRegistration()
                            trySend(Unit)
                        }
                }

                awaitClose {
                    @Suppress("TooGenericExceptionCaught")
                    try {
                        manager.removeOnSubscriptionsChangedListener(listener)
                    } catch (e: Exception) {
                        if (e !is CancellationException) {
                            AppLogger.w("Failed to unregister SubscriptionsChangedListener in awaitClose", e)
                        }
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
                throw SecurityException("READ_PHONE_STATE permission missing")
            }

        /**
         * Discovers active SIM cards and returns a structured result.
         *
         * The [isStable] parameter applies to ICCID-based identities. If a SIM card lacks an ICCID,
         * its identity is constructed from its subscription ID and is assigned `isStable = false`.
         *
         * @param seed Secret seed for hashing ICCID.
         * @param isStable Whether real/stable identity seed was used.
         * @return [SimDiscoveryResult] representing the discovery outcome.
         */
        fun discoverSims(
            seed: String,
            isStable: Boolean = true,
        ): SimDiscoveryResult {
            val manager = subscriptionManager
            val hasPermission = permissionManager.hasPhoneStatePermission()

            return when {
                manager == null -> SimDiscoveryResult.Unsupported
                !hasPermission -> SimDiscoveryResult.PermissionMissing
                else -> {
                    @Suppress("TooGenericExceptionCaught")
                    try {
                        val subscriptions = getActiveSubscriptions()
                        val defaultDataSubId = getDefaultDataSubscriptionId()
                        val defaultVoiceSubId = getDefaultVoiceSubscriptionId()

                        val discoveredSims =
                            subscriptions.map { info ->
                                val identity = SimIdentityUtils.createIdentityFromInfo(info, seed, isStable)
                                ActiveSimInfo(
                                    identity = identity,
                                    slotIndex = info.simSlotIndex,
                                    subscriptionId = info.subscriptionId,
                                    iccId = info.iccId,
                                    displayName = info.displayName?.toString(),
                                    carrierName = info.carrierName?.toString(),
                                    isDefaultData = info.subscriptionId == defaultDataSubId,
                                    isDefaultVoice = info.subscriptionId == defaultVoiceSubId,
                                )
                            }
                        SimDiscoveryResult.Available(discoveredSims)
                    } catch (e: SecurityException) {
                        AppLogger.w("Sim discovery failed: security restriction", e)
                        SimDiscoveryResult.PermissionMissing
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        // Operation must complete without propagating exception for SIM discovery.
                        AppLogger.e("Sim discovery failed", e)
                        SimDiscoveryResult.Failed(SimDiscoveryFailureReason.API_ERROR)
                    }
                }
            }
        }

        fun getDefaultDataSubscriptionId(): Int = permissionManager.getDefaultDataSubscriptionId()

        fun getDefaultVoiceSubscriptionId(): Int = permissionManager.getDefaultVoiceSubscriptionId()
    }
