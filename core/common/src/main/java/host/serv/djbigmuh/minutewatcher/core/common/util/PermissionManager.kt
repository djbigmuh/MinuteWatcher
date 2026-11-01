package host.serv.djbigmuh.minutewatcher.core.common.util

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

        // MissingPermission: hasPhoneStatePermission() check is performed before accessing activeSubscriptionInfoList.
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
            val appOps = context.getSystemService(AppOpsManager::class.java)
            val mode =
                try {
                    if (appOps == null) {
                        AppOpsManager.MODE_ERRORED
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        appOps.unsafeCheckOpNoThrow(
                            AppOpsManager.OPSTR_GET_USAGE_STATS,
                            Process.myUid(),
                            context.packageName,
                        )
                    } else {
                        // checkOpNoThrow is the documented check API on API 26-28; deprecated since API 29,
                        // where unsafeCheckOpNoThrow is used instead.
                        @Suppress("DEPRECATION")
                        appOps.checkOpNoThrow(
                            AppOpsManager.OPSTR_GET_USAGE_STATS,
                            Process.myUid(),
                            context.packageName,
                        )
                    }
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    // Operation must complete without propagating exception.
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
