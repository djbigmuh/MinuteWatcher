package host.serv.djbigmuh.minutewatcher.core.data

import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import host.serv.djbigmuh.minutewatcher.core.common.util.PermissionManager
import host.serv.djbigmuh.minutewatcher.core.model.TrackingStatus
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrafficPermissionChecker
    @Inject
    constructor(
        private val permissionManager: PermissionManager,
    ) {
        /**
         * Checks required permissions for traffic tracking.
         *
         * @param onError Callback to handle any detected permission or support errors.
         * @return True if all permissions are granted and telephony is supported.
         */
        fun checkPermissions(onError: (TrackingStatus) -> Unit): Boolean {
            val error =
                when {
                    !permissionManager.isTelephonySupported() -> {
                        AppLogger.w("TrafficPermissionChecker: Telephony/SubscriptionManager not supported")
                        TrackingStatus.Error(
                            TrackingStatus.ErrorType.NOT_SUPPORTED,
                            "SubscriptionManager unavailable",
                        )
                    }

                    !permissionManager.hasUsageStatsPermission() -> {
                        AppLogger.w("TrafficPermissionChecker: Usage Access permission missing")
                        TrackingStatus.Error(
                            TrackingStatus.ErrorType.PERMISSION_MISSING,
                            "Usage Access missing",
                        )
                    }

                    !permissionManager.hasPhoneStatePermission() -> {
                        AppLogger.w("TrafficPermissionChecker: Phone State permission missing")
                        TrackingStatus.Error(
                            TrackingStatus.ErrorType.PERMISSION_MISSING,
                            "Phone State missing",
                        )
                    }

                    else -> null
                }
            error?.let { onError(it) }
            return error == null
        }
    }
