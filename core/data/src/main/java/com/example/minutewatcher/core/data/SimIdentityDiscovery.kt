package com.example.minutewatcher.core.data

import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.model.Constants.SimConstants
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
        fun getActiveSubscriptions(): List<SubscriptionInfo> =
            if (permissionManager.hasPhoneStatePermission()) {
                permissionManager
                    .getActiveSubscriptions()
                    .filter { it.simSlotIndex in SimConstants.SUPPORTED_SLOT_INDICES }
            } else {
                emptyList()
            }

        fun getDefaultDataSubscriptionId(): Int = permissionManager.getDefaultDataSubscriptionId()

        fun getDefaultVoiceSubscriptionId(): Int = permissionManager.getDefaultVoiceSubscriptionId()
    }
