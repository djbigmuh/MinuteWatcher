package com.example.minutewatcher.feature.main

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.example.minutewatcher.core.model.PermissionUiState

@Immutable
data class TrackingBannerState(
    val phonePermissionState: PermissionUiState = PermissionUiState.NOT_REQUESTED,
    val showUsageAccessBanner: Boolean = false,
    val showTrafficErrorBanner: Boolean = false,
    val showTrafficFallbackBanner: Boolean = false,
    val trafficFallbackSlot: Int = -1,
    val showCallsErrorBanner: Boolean = false,
    val showAppSearchErrorBanner: Boolean = false,
    val appSearchErrorType: com.example.minutewatcher.core.model.TrackingStatus.ErrorType? = null,
    val appSearchBannerDismissed: Boolean = false,
    val showShortcutsErrorBanner: Boolean = false,
    val showWidgetErrorBanner: Boolean = false,
    val showInaccurateCallsBanner: Boolean = false,
    val notificationPermissionState: PermissionUiState = PermissionUiState.NOT_REQUESTED,
    val observationFailed: Boolean = false,
    val isStorageDegraded: Boolean = false,
)

@Stable
// Используем @Stable вместо @Immutable для гарантированной стабильности лямбд в Compose.
data class TrackingBannerCallbacks(
    val onGrantPhonePermission: () -> Unit,
    val onShowUsageAccessDialog: () -> Unit,
    val onOpenAppSettings: () -> Unit,
    val onRetryAppSearch: () -> Unit,
    val onDismissAppSearch: () -> Unit,
    val onRetryCalls: () -> Unit,
    val onRetryShortcuts: () -> Unit,
    val onRetryTraffic: () -> Unit,
    val onGrantNotificationPermission: () -> Unit,
    val onDismissWidgetError: () -> Unit,
    val onDismissInaccurateCalls: () -> Unit,
    val onRetryBanners: () -> Unit,
)
