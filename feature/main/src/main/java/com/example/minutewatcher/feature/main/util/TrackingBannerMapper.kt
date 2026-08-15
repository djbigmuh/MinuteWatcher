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
            determinePhonePermissionState(
                phoneGranted,
                phonePermanentlyDenied,
                settings.phonePermissionRequested,
            )

        if (!settings.onboardingCompleted) {
            return TrackingBannerState(
                phonePermissionState = phonePermissionState,
            )
        }

        return TrackingBannerState(
            phonePermissionState = phonePermissionState,
            showUsageAccessBanner = !usageGranted,
            showTrafficErrorBanner = isTrafficError(traffic),
            showTrafficFallbackBanner = isTrafficFallback(traffic),
            trafficFallbackSlot = getTrafficFallbackSlot(traffic),
            showCallsErrorBanner = isCallsError(calls),
            showAppSearchErrorBanner = isAppSearchError(appsearch),
            showShortcutsErrorBanner = shortcuts is TrackingStatus.Error,
            showWidgetErrorBanner = settings.widgetLimitExceeded,
            showInaccurateCallsBanner = settings.inaccurateCallAttributionsCount >= 3,
            notificationPermissionState =
                determineNotificationPermissionState(
                    notificationsGranted,
                    notificationPermanentlyDenied,
                    settings,
                ),
        )
    }

    private fun determinePhonePermissionState(
        granted: Boolean,
        permanentlyDenied: Boolean,
        requested: Boolean,
    ): PermissionUiState =
        when {
            granted -> PermissionUiState.GRANTED
            permanentlyDenied -> PermissionUiState.PERMANENTLY_DENIED
            requested -> PermissionUiState.DENIED
            else -> PermissionUiState.NOT_REQUESTED
        }

    private fun determineNotificationPermissionState(
        granted: Boolean,
        permanentlyDenied: Boolean,
        settings: AppSettings,
    ): PermissionUiState {
        val showNotifications = settings.notifyLowMinutes || settings.notifyLowTraffic
        return when {
            !showNotifications || granted -> PermissionUiState.GRANTED
            permanentlyDenied -> PermissionUiState.PERMANENTLY_DENIED
            settings.notificationPermissionRequested -> PermissionUiState.DENIED
            else -> PermissionUiState.NOT_REQUESTED
        }
    }

    private fun isTrafficError(status: TrackingStatus?): Boolean =
        status is TrackingStatus.Error &&
            status.type != TrackingStatus.ErrorType.PERMISSION_MISSING &&
            status.type != TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION

    private fun isTrafficFallback(status: TrackingStatus?): Boolean =
        status is TrackingStatus.Error &&
            status.type == TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION

    private fun getTrafficFallbackSlot(status: TrackingStatus?): Int =
        if (status is TrackingStatus.Error &&
            status.type == TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION
        ) {
            status.slotIndex ?: -1
        } else {
            -1
        }

    private fun isCallsError(status: TrackingStatus?): Boolean =
        status is TrackingStatus.Error &&
            status.type != TrackingStatus.ErrorType.PERMISSION_MISSING

    private fun isAppSearchError(status: TrackingStatus?): Boolean =
        status is TrackingStatus.Error &&
            status.type != TrackingStatus.ErrorType.NOT_SUPPORTED
}
