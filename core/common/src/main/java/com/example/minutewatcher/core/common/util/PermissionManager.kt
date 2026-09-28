package com.example.minutewatcher.core.common.util

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Utility for managing and checking Android system permissions.
 *
 * **Thread safety:** All methods are safe to call from any thread.
 * System service access is handled by the Android Framework.
 *
 * **Logging Contract:** Permission state is logged once when changed, rather than on every check.
 */
@Singleton
class PermissionManager
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val subscriptionManager: SubscriptionManager?,
    ) {
        private val phoneStatePermissionState = AtomicReference<Boolean?>(null)
        private val notificationPermissionState = AtomicReference<Boolean?>(null)
        private val usageStatsPermissionState = AtomicReference<Boolean?>(null)

        /**
         * Returns true if the device supports telephony services (SIM cards).
         */
        fun isTelephonySupported(): Boolean =
            subscriptionManager != null &&
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

        fun hasPhoneStatePermission(): Boolean {
            val granted =
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_PHONE_STATE,
                ) == PackageManager.PERMISSION_GRANTED
            logPermissionStateChange("Phone state", phoneStatePermissionState, granted)
            return granted
        }

        // @Suppress("MissingPermission") is required because Lint does not recognize
        // the explicit check performed via ContextCompat.checkSelfPermission.

        /**
         * Returns an empty list if [Manifest.permission.READ_PHONE_STATE] is missing or [SubscriptionManager]
         * is unavailable. Otherwise reads active subscription info list from [SubscriptionManager].
         */
        @Suppress("MissingPermission")
        fun getActiveSubscriptions(): List<SubscriptionInfo> {
            val manager = subscriptionManager
            val hasPermission = hasPhoneStatePermission()

            return if (manager != null && hasPermission) {
                try {
                    manager.activeSubscriptionInfoList.orEmpty()
                } catch (e: SecurityException) {
                    AppLogger.e("PermissionManager: SecurityException in getActiveSubscriptions", e)
                    emptyList()
                }
            } else {
                emptyList()
            }
        }

        /**
         * Returns -1 if [Manifest.permission.READ_PHONE_STATE] is missing or [SubscriptionManager]
         * is unavailable. Otherwise reads default voice subscription ID from [SubscriptionManager].
         */
        fun getDefaultVoiceSubscriptionId(): Int {
            val manager = subscriptionManager
            val hasPermission = hasPhoneStatePermission()

            return if (manager != null && hasPermission) {
                try {
                    SubscriptionManager.getDefaultVoiceSubscriptionId()
                } catch (e: SecurityException) {
                    AppLogger.e("PermissionManager: SecurityException in getDefaultVoiceSubscriptionId", e)
                    -1
                }
            } else {
                -1
            }
        }

        /**
         * Returns -1 if [Manifest.permission.READ_PHONE_STATE] is missing or [SubscriptionManager]
         * is unavailable. Otherwise reads default data subscription ID from [SubscriptionManager].
         */
        fun getDefaultDataSubscriptionId(): Int {
            val manager = subscriptionManager
            val hasPermission = hasPhoneStatePermission()

            return if (manager != null && hasPermission) {
                try {
                    SubscriptionManager.getDefaultDataSubscriptionId()
                } catch (e: SecurityException) {
                    AppLogger.e("PermissionManager: SecurityException in getDefaultDataSubscriptionId", e)
                    -1
                }
            } else {
                -1
            }
        }

        fun hasNotificationPermission(): Boolean {
            val granted =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED
                } else {
                    true
                }
            logPermissionStateChange("Notification", notificationPermissionState, granted)
            return granted
        }

        fun hasUsageStatsPermission(): Boolean {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode =
                try {
                    // @Suppress("DEPRECATION") handles API 29+ unsafeCheckOpNoThrow deprecation warnings
                    // and API < 29 checkOpNoThrow.
                    @Suppress("DEPRECATION")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        appOps.unsafeCheckOpNoThrow(
                            AppOpsManager.OPSTR_GET_USAGE_STATS,
                            Process.myUid(),
                            context.packageName,
                        )
                    } else {
                        appOps.checkOpNoThrow(
                            AppOpsManager.OPSTR_GET_USAGE_STATS,
                            Process.myUid(),
                            context.packageName,
                        )
                    }
                } catch (e: Exception) {
                    AppLogger.e("PermissionManager: Failed to check GET_USAGE_STATS op", e)
                    AppOpsManager.MODE_IGNORED
                }
            val granted = mode == AppOpsManager.MODE_ALLOWED
            logPermissionStateChange("Usage stats", usageStatsPermissionState, granted)
            return granted
        }

        private fun logPermissionStateChange(
            permissionName: String,
            stateRef: AtomicReference<Boolean?>,
            granted: Boolean,
        ) {
            val previousState = stateRef.getAndSet(granted)
            if (previousState != granted) {
                AppLogger.d("$permissionName permission state: ${if (granted) "Granted" else "Denied"}")
            }
        }
    }
