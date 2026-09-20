package com.example.minutewatcher.feature.main.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.minutewatcher.feature.main.TrackingBannerCallbacks
import com.example.minutewatcher.feature.main.TrackingBannerState

@Composable
fun TrackingBanners(
    state: TrackingBannerState,
    callbacks: TrackingBannerCallbacks,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ObservationErrorBanner(
            state = state,
            onRetry = callbacks.onRetryWidgetObservation,
        )
        StorageDegradedBanner(
            state = state,
            onRetry = callbacks.onRetrySimDiscovery,
        )
        PermissionBanners(state, callbacks)
        ErrorBanners(state, callbacks)
        StatusBanners(state, callbacks)
    }
}

@Composable
private fun PermissionBanners(
    state: TrackingBannerState,
    callbacks: TrackingBannerCallbacks,
) {
    PhonePermissionBanner(
        state = state,
        onGrantPhonePermission = callbacks.onGrantPhonePermission,
        onOpenAppSettings = callbacks.onOpenAppSettings,
    )
    UsageAccessBanner(
        state = state,
        onShowUsageAccessDialog = callbacks.onShowUsageAccessDialog,
    )
    NotificationPermissionBanner(
        state = state,
        onGrantNotificationPermission = callbacks.onGrantNotificationPermission,
        onOpenAppSettings = callbacks.onOpenAppSettings,
    )
}

@Composable
private fun ErrorBanners(
    state: TrackingBannerState,
    callbacks: TrackingBannerCallbacks,
) {
    TrafficErrorBanner(
        state = state,
        onRetryTraffic = callbacks.onRetryTraffic,
    )
    TrafficFallbackBanner(
        state = state,
        onRetryTraffic = callbacks.onRetryTraffic,
    )
    CallsErrorBanner(
        state = state,
        onRetryCalls = callbacks.onRetryCalls,
    )
    AppSearchErrorBanner(
        state = state,
        onRetryAppSearch = callbacks.onRetryAppSearch,
        onDismissAppSearch = callbacks.onDismissAppSearch,
    )
    ShortcutsErrorBanner(
        state = state,
        onRetryShortcuts = callbacks.onRetryShortcuts,
    )
}

@Composable
private fun StatusBanners(
    state: TrackingBannerState,
    callbacks: TrackingBannerCallbacks,
) {
    WidgetErrorBanner(
        state = state,
        onDismiss = callbacks.onDismissWidgetError,
    )
    InaccurateCallsBanner(
        state = state,
        onDismiss = callbacks.onDismissInaccurateCalls,
    )
}
