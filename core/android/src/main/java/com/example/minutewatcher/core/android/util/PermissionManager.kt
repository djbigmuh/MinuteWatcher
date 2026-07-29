package com.example.minutewatcher.core.android.util

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
import com.example.minutewatcher.core.common.util.AppLogger
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

        fun hasPhoneStatePermission(): Boolean =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_STATE,
            ) == PackageManager.PERMISSION_GRANTED

        // Permission is checked manually using hasPhoneStatePermission() before access.
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

        // Permission is checked manually using hasPhoneStatePermission() before access.
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

        // Permission is checked manually using hasPhoneStatePermission() before access.
        @SuppressLint("MissingPermission")
        fun getDefaultDataSubscriptionId(): Int =
            try {
                if (hasPhoneStatePermission()) {
                    SubscriptionManager.getDefaultDataSubscriptionId()
                } else {
                    -1
                }
            } catch (e: SecurityException) {
                AppLogger.e("SecurityException in getDefaultVoiceSubscriptionId", e)
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
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
                    context.packageName
                )
            }
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
