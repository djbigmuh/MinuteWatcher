package com.example.minutewatcher.feature.main.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.minutewatcher.core.resources.R
import com.example.minutewatcher.feature.main.TrackingBannerState

@Composable
internal fun SimDiscoveryErrorBanner(
    state: TrackingBannerState,
    onRetrySimDiscovery: () -> Unit,
) {
    if (state.showSimDiscoveryErrorBanner) {
        TrackingBanner(
            text = stringResource(id = R.string.tracking_banner_sim_discovery_error),
            actionText = stringResource(id = R.string.action_retry),
            onAction = onRetrySimDiscovery,
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
internal fun TrafficErrorBanner(
    state: TrackingBannerState,
    onRetryTraffic: () -> Unit,
) {
    if (state.showTrafficErrorBanner) {
        TrackingBanner(
            text = stringResource(id = R.string.tracking_banner_traffic_error),
            actionText = stringResource(id = R.string.action_retry),
            onAction = onRetryTraffic,
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
internal fun CallsErrorBanner(
    state: TrackingBannerState,
    onRetryCalls: () -> Unit,
) {
    if (state.showCallsErrorBanner) {
        TrackingBanner(
            text = stringResource(id = R.string.tracking_banner_calls_error),
            actionText = stringResource(id = R.string.action_retry),
            onAction = onRetryCalls,
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
internal fun CallsAttributionErrorBanner(
    state: TrackingBannerState,
    onRetryCalls: () -> Unit,
) {
    if (state.showCallsAttributionErrorBanner) {
        TrackingBanner(
            text = stringResource(id = R.string.tracking_banner_calls_attribution_error),
            actionText = stringResource(id = R.string.action_retry),
            onAction = onRetryCalls,
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
internal fun AppSearchErrorBanner(
    state: TrackingBannerState,
    onRetryAppSearch: () -> Unit,
    onDismissAppSearch: () -> Unit,
) {
    if (state.showAppSearchErrorBanner) {
        TrackingBanner(
            text = stringResource(id = R.string.tracking_banner_appsearch_error),
            actionText = stringResource(id = R.string.action_retry),
            onAction = onRetryAppSearch,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            onDismiss = onDismissAppSearch,
        )
    }
}

@Composable
internal fun ShortcutsErrorBanner(
    state: TrackingBannerState,
    onRetryShortcuts: () -> Unit,
) {
    if (state.showShortcutsErrorBanner) {
        TrackingBanner(
            text = stringResource(id = R.string.tracking_banner_shortcuts_error),
            actionText = stringResource(id = R.string.action_retry),
            onAction = onRetryShortcuts,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun WidgetErrorBanner(
    state: TrackingBannerState,
    onDismiss: () -> Unit,
) {
    if (state.showWidgetErrorBanner) {
        TrackingBanner(
            text = stringResource(id = R.string.tracking_banner_widget_error),
            actionText = stringResource(id = R.string.action_ok),
            onAction = onDismiss,
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
internal fun InaccurateCallsBanner(
    state: TrackingBannerState,
    onDismiss: () -> Unit,
) {
    if (state.showInaccurateCallsBanner) {
        TrackingBanner(
            text = stringResource(id = R.string.tracking_banner_inaccurate_calls),
            actionText = stringResource(id = R.string.action_ok),
            onAction = onDismiss,
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

@Composable
internal fun ObservationErrorBanner(
    state: TrackingBannerState,
    onRetry: () -> Unit,
) {
    if (state.observationFailed) {
        TrackingBanner(
            text = stringResource(id = R.string.tracking_banner_observation_failed),
            actionText = stringResource(id = R.string.action_retry),
            onAction = onRetry,
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
internal fun StorageDegradedBanner(
    state: TrackingBannerState,
    onRetry: () -> Unit,
) {
    if (state.isStorageDegraded) {
        TrackingBanner(
            text = stringResource(id = R.string.tracking_banner_storage_degraded),
            actionText = stringResource(id = R.string.action_retry),
            onAction = onRetry,
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}
