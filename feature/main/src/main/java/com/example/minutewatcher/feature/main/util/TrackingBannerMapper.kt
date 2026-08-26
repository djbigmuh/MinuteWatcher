package com.example.minutewatcher.feature.main.util

import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.PermissionUiState
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.feature.main.TrackingBannerState

object TrackingBannerMapper {
    @Suppress("LongParameterList")
    fun mapToBannerState(
        statusMap: Map<String, TrackingStatus>,
        phoneGranted: Boolean,
        usageGranted: Boolean,
        notificationsGranted: Boolean,
        phonePermanentlyDenied: Boolean,
        notificationPermanentlyDenied: Boolean,
        settings: AppSettings,
        appSearchRetryCount: Int = 0,
        appSearchBannerDismissed: Boolean = false,
        isStorageDegraded: Boolean = false,
    ): TrackingBannerState {
        val traffic = statusMap["traffic"]
        val appsearch = statusMap["appsearch"]
        val shortcuts = statusMap["shortcuts"]
        val calls = statusMap["calls"]

        val observationFailed =
            (
                (statusMap["widget_observation"] as? TrackingStatus.Error)?.type ==
                    TrackingStatus.ErrorType.DATABASE_ERROR
                ) ||
                isStorageDegraded

        val phonePermissionState =
            determinePhonePermissionState(
                phoneGranted,
                phonePermanentlyDenied,
                settings.wasPhonePermissionRequestedBefore,
            )

        if (!settings.onboardingCompleted) {
            return TrackingBannerState(
                phonePermissionState = phonePermissionState,
            )
        }

        val appSearchErrorType = (appsearch as? TrackingStatus.Error)?.type

        return TrackingBannerState(
            phonePermissionState = phonePermissionState,
            showUsageAccessBanner = !usageGranted,
            showTrafficErrorBanner = isTrafficError(traffic),
            showTrafficFallbackBanner = isTrafficFallback(traffic),
            trafficFallbackSlot = getTrafficFallbackSlot(traffic),
            showCallsErrorBanner = isCallsError(calls),
            showAppSearchErrorBanner =
                isAppSearchErrorToShow(
                    appsearch,
                    appSearchRetryCount,
                    appSearchBannerDismissed,
                ),
            appSearchErrorType = appSearchErrorType,
            appSearchBannerDismissed = appSearchBannerDismissed,
            showShortcutsErrorBanner = isShortcutsError(shortcuts),
            showWidgetErrorBanner = settings.widgetLimitExceeded,
            showInaccurateCallsBanner = settings.inaccurateCallAttributionsCount >= 3,
            notificationPermissionState =
                determineNotificationPermissionState(
                    notificationsGranted,
                    notificationPermanentlyDenied,
                    settings,
                ),
            observationFailed = observationFailed,
            isStorageDegraded = isStorageDegraded,
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
        (status is TrackingStatus.Error) &&
            (status.type != TrackingStatus.ErrorType.PERMISSION_MISSING) &&
            (status.type != TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION)

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

    private fun isAppSearchErrorToShow(
        status: TrackingStatus?,
        retryCount: Int,
        dismissed: Boolean,
    ): Boolean {
        if (status !is TrackingStatus.Error || dismissed) return false

        return when (status.type) {
            TrackingStatus.ErrorType.NOT_SUPPORTED -> false
            TrackingStatus.ErrorType.DATABASE_ERROR -> retryCount < MAX_APPSEARCH_RETRY_COUNT
            else -> true
        }
    }

    /**
     * Максимальное количество автоматических попыток восстановления AppSearch,
     * после которых баннер ошибки скрывается. Пользователь не может
     * повлиять на ошибку схемы БД или отсутствие системного сервиса,
     * поэтому перманентный баннер только раздражает.
     *
     * Счётчик сбрасывается при перезапуске приложения.
     */
    private const val MAX_APPSEARCH_RETRY_COUNT = 3

    private fun isShortcutsError(status: TrackingStatus?): Boolean =
        status is TrackingStatus.Error &&
            status.type != TrackingStatus.ErrorType.NOT_SUPPORTED
}
