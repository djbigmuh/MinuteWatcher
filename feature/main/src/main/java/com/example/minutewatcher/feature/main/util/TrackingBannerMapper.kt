package com.example.minutewatcher.feature.main.util

import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.PermissionUiState
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.feature.main.TrackingBannerState

object TrackingBannerMapper {
    fun mapToBannerState(
        statusMap: Map<String, TrackingStatus>,
        phoneGranted: Boolean,
        usageGranted: Boolean,
        notificationsGranted: Boolean,
        phonePermanentlyDenied: Boolean,
        notificationPermanentlyDenied: Boolean,
        settings: AppSettings,
    ): TrackingBannerState {
        val traffic = statusMap["traffic"]
        val appsearch = statusMap["appsearch"]
        val shortcuts = statusMap["shortcuts"]
        val calls = statusMap["calls"]

        val phonePermissionState =
            when {
                phoneGranted -> PermissionUiState.GRANTED
                phonePermanentlyDenied -> PermissionUiState.PERMANENTLY_DENIED
                settings.phonePermissionRequested -> PermissionUiState.DENIED
                else -> PermissionUiState.NOT_REQUESTED
            }

        val showNotifications = settings.notifyLowMinutes || settings.notifyLowTraffic
        val notificationPermissionState =
            when {
                !showNotifications || notificationsGranted -> PermissionUiState.GRANTED
                notificationPermanentlyDenied -> PermissionUiState.PERMANENTLY_DENIED
                settings.notificationPermissionRequested -> PermissionUiState.DENIED
                else -> PermissionUiState.NOT_REQUESTED
            }

        return TrackingBannerState(
            phonePermissionState = phonePermissionState,
            showUsageAccessBanner = !usageGranted,
            showTrafficErrorBanner =
                (
                    traffic is TrackingStatus.Error &&
                        traffic.type != TrackingStatus.ErrorType.PERMISSION_MISSING &&
                        traffic.type != TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION
                ),
            showTrafficFallbackBanner =
                (
                    traffic is TrackingStatus.Error &&
                        traffic.type == TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION
                ),
            trafficFallbackSlot =
                if (traffic is TrackingStatus.Error && traffic.type == TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION) {
                    traffic.message?.toIntOrNull() ?: -1
                } else {
                    -1
                },
            showCallsErrorBanner =
                (
                    calls is TrackingStatus.Error &&
                        calls.type != TrackingStatus.ErrorType.PERMISSION_MISSING
                ),
            showAppSearchErrorBanner = appsearch is TrackingStatus.Error,
            showShortcutsErrorBanner = shortcuts is TrackingStatus.Error,
            showWidgetErrorBanner = settings.widgetLimitExceeded,
            showInaccurateCallsBanner = settings.inaccurateCallAttributionsCount >= 3,
            notificationPermissionState = notificationPermissionState,
        )
    }
}
