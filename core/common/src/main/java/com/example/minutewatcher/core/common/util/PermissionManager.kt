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
        fun getActiveSubscriptions(): List<SubscriptionInfo> {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_PHONE_STATE,
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                return try {
                    subscriptionManager?.activeSubscriptionInfoList ?: emptyList()
                } catch (e: SecurityException) {
                    AppLogger.e("SecurityException in getActiveSubscriptions", e)
                    emptyList()
                }
            }
            return emptyList()
        }

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
                    AppLogger.e("SecurityException in getDefaultVoiceSubscriptionId", e)
                    -1
                }
            }
            return -1
        }

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
                    AppLogger.e("SecurityException in getDefaultDataSubscriptionId", e)
                    -1
                }
            }
            return -1
        }

        fun hasNotificationPermission(): Boolean =
            // API 33+ required for POST_NOTIFICATIONS runtime permission.
            // See: https://developer.android.com/training/notify-user/runtime-permissions
            // minSdk is 26, so this branch is reachable and required.
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
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                        // On API 34+, checkOpNoThrow is recommended again and is not deprecated.
                        appOps.checkOpNoThrow(
                            AppOpsManager.OPSTR_GET_USAGE_STATS,
                            Process.myUid(),
                            context.packageName,
                        )
                    }
                    else -> {
                        // On API 30+, checkOpNoThrow is the preferred safe version.
                        // On API 29, unsafeCheckOpNoThrow is used as checkOpNoThrow was deprecated.
                        // See: https://developer.android.com/reference/android/app/AppOpsManager#checkOpNoThrow(java.lang.String,%20int,%20java.lang.String)
                        @Suppress("DEPRECATION")
                        when {
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                                appOps.checkOpNoThrow(
                                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                                    Process.myUid(),
                                    context.packageName,
                                )
                            }

                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                                appOps.unsafeCheckOpNoThrow(
                                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                                    Process.myUid(),
                                    context.packageName,
                                )
                            }

                            else -> {
                                appOps.checkOpNoThrow(
                                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                                    Process.myUid(),
                                    context.packageName,
                                )
                            }
                        }
                    }
                }
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
