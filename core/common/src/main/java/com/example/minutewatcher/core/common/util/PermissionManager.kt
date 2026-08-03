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
        private val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager

        init {
            if (subscriptionManager == null) {
                AppLogger.e("SubscriptionManager is null. Telephony features will be unavailable.")
            }
        }

        /**
         * Returns true if the device supports telephony services (SIM cards).
         */
        fun isTelephonySupported(): Boolean = subscriptionManager != null

        fun hasPhoneStatePermission(): Boolean =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_STATE,
            ) == PackageManager.PERMISSION_GRANTED

        // MissingPermission is suppressed because hasPhoneStatePermission() is explicitly checked before access.
        // This method provides a safe way to get active subscriptions without crashing if permission is revoked.
        @SuppressLint("MissingPermission")
        fun getActiveSubscriptions(): List<SubscriptionInfo> =
            try {
                if (hasPhoneStatePermission()) {
                    subscriptionManager?.activeSubscriptionInfoList ?: emptyList()
                } else {
                    emptyList()
                }
            } catch (e: SecurityException) {
                AppLogger.e("SecurityException in getActiveSubscriptions", e)
                emptyList()
            }

        // MissingPermission is suppressed because hasPhoneStatePermission() is explicitly checked before access.
        // Returns -1 if permission is missing or an error occurs.
        @SuppressLint("MissingPermission")
        fun getDefaultVoiceSubscriptionId(): Int =
            try {
                if (hasPhoneStatePermission()) {
                    SubscriptionManager.getDefaultVoiceSubscriptionId()
                } else {
                    -1
                }
            } catch (e: SecurityException) {
                AppLogger.e("SecurityException in getDefaultVoiceSubscriptionId", e)
                -1
            }

        // MissingPermission is suppressed because hasPhoneStatePermission() is explicitly checked before access.
        // Returns -1 if permission is missing or an error occurs.
        @SuppressLint("MissingPermission")
        fun getDefaultDataSubscriptionId(): Int =
            try {
                if (hasPhoneStatePermission()) {
                    SubscriptionManager.getDefaultDataSubscriptionId()
                } else {
                    -1
                }
            } catch (e: SecurityException) {
                AppLogger.e("SecurityException in getDefaultDataSubscriptionId", e)
                -1
            }

        fun hasNotificationPermission(): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }

        fun hasUsageStatsPermission(): Boolean {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // unsafeCheckOpNoThrow is preferred on Q+ for better security/accuracy in some contexts.
                    // It is deprecated in API 33 but remains the standard way to check without "noting" the op.
                    @Suppress("DEPRECATION")
                    appOps.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(),
                        context.packageName,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    appOps.checkOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(),
                        context.packageName,
                    )
                }
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
