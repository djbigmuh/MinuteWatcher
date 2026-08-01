package com.example.minutewatcher.feature.main

import com.example.minutewatcher.core.model.PermissionUiState

data class TrackingBannerState(
    val phonePermissionState: PermissionUiState = PermissionUiState.GRANTED,
    val showUsageAccessBanner: Boolean = false,
    val showTrafficErrorBanner: Boolean = false,
    val showCallsErrorBanner: Boolean = false,
    val showAppSearchErrorBanner: Boolean = false,
    val showShortcutsErrorBanner: Boolean = false,
    val notificationPermissionState: PermissionUiState = PermissionUiState.GRANTED,
)

data class TrackingBannerCallbacks(
    val onGrantPhonePermission: () -> Unit,
    val onShowUsageAccessDialog: () -> Unit,
    val onOpenAppSettings: () -> Unit,
    val onRetryAppSearch: () -> Unit,
    val onRetryCalls: () -> Unit,
    val onRetryShortcuts: () -> Unit,
    val onRetryTraffic: () -> Unit,
    val onGrantNotificationPermission: () -> Unit,
)
