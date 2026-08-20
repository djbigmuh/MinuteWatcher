package com.example.minutewatcher.core.common.util

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PermissionManager
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        private val subscriptionManager =
            context.getSystemService(
                Context.TELEPHONY_SUBSCRIPTION_SERVICE,
            ) as? SubscriptionManager

        init {
            if (subscriptionManager == null) {
                AppLogger.e("PermissionManager: SubscriptionManager is null. Telephony features will be unavailable.")
            }
        }

        /**
         * Returns true if the device supports telephony services (SIM cards).
         */
        fun isTelephonySupported(): Boolean = subscriptionManager != null

        fun hasPhoneStatePermission(): Boolean {
            val granted =
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_PHONE_STATE,
                ) == PackageManager.PERMISSION_GRANTED
            AppLogger.d("Checking READ_PHONE_STATE permission: ${if (granted) "Granted" else "Denied"}")
            return granted
        }

        // MissingPermission is suppressed because hasPhoneStatePermission() is explicitly checked before access.
        // We use explicit check inside the method instead of @RequiresPermission to avoid propagating
        // the requirement to all callers, allowing for graceful failure at the call site.
        @SuppressLint("MissingPermission")
        fun getActiveSubscriptions(): List<SubscriptionInfo> {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_PHONE_STATE,
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                return try {
                    subscriptionManager?.activeSubscriptionInfoList ?: emptyList()
                } catch (e: SecurityException) {
                    AppLogger.e("PermissionManager: SecurityException in getActiveSubscriptions", e)
                    emptyList()
                }
            }
            return emptyList()
        }

        // MissingPermission is suppressed because a manual check is performed before calling the API.
        // This prevents Lint from requiring @RequiresPermission on this method.
        @SuppressLint("MissingPermission")
        fun getDefaultVoiceSubscriptionId(): Int {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_PHONE_STATE,
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                return try {
                    SubscriptionManager.getDefaultVoiceSubscriptionId()
                } catch (e: SecurityException) {
                    AppLogger.e("PermissionManager: SecurityException in getDefaultVoiceSubscriptionId", e)
                    -1
                }
            }
            return -1
        }

        // MissingPermission is suppressed because a manual check is performed before calling the API.
        // This prevents Lint from requiring @RequiresPermission on this method.
        @SuppressLint("MissingPermission")
        fun getDefaultDataSubscriptionId(): Int {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_PHONE_STATE,
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                return try {
                    SubscriptionManager.getDefaultDataSubscriptionId()
                } catch (e: SecurityException) {
                    AppLogger.e("PermissionManager: SecurityException in getDefaultDataSubscriptionId", e)
                    -1
                }
            }
            return -1
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
            AppLogger.d("Checking POST_NOTIFICATIONS permission: ${if (granted) "Granted" else "Denied"}")
            return granted
        }

        fun hasUsageStatsPermission(): Boolean {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode =
                try {
                    // API 37: checkOpNoThrow is preferred for local UID checks.
                    // Fail-closed fallback: custom ROMs or unexpected failures default to denied.
                    appOps.checkOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(),
                        context.packageName,
                    )
                } catch (e: Exception) {
                    AppLogger.e("PermissionManager: Failed to check GET_USAGE_STATS op", e)
                    AppOpsManager.MODE_IGNORED
                }
            val granted = mode == AppOpsManager.MODE_ALLOWED
            AppLogger.d("Checking GET_USAGE_STATS permission: ${if (granted) "Granted" else "Denied"}")
            return granted
        }
    }
