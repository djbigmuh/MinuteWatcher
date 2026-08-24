package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrafficPermissionChecker @Inject constructor(
    private val permissionManager: PermissionManager,
) {
    fun checkPermissions(statusFlow: MutableStateFlow<TrackingStatus>): Boolean {
        val error =
            when {
                !permissionManager.isTelephonySupported() ->
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.UNKNOWN,
                        "SubscriptionManager unavailable",
                    )
                !permissionManager.hasUsageStatsPermission() ->
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.PERMISSION_MISSING,
                        "Usage Access missing",
                    )
                !permissionManager.hasPhoneStatePermission() ->
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.PERMISSION_MISSING,
                        "Phone State missing",
                    )
                else -> null
            }
        error?.let { statusFlow.value = it }
        return error == null
    }
}
