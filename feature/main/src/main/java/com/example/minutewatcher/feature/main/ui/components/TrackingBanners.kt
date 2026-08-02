package com.example.minutewatcher.feature.main.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.minutewatcher.core.model.PermissionUiState
import com.example.minutewatcher.core.resources.R
import com.example.minutewatcher.feature.main.TrackingBannerCallbacks
import com.example.minutewatcher.feature.main.TrackingBannerState

@Composable
fun TrackingBanners(
    state: TrackingBannerState,
    callbacks: TrackingBannerCallbacks,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
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

@Composable
private fun PhonePermissionBanner(
    state: TrackingBannerState,
    onGrantPhonePermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    if (state.phonePermissionState != PermissionUiState.GRANTED) {
        val isPermanentlyDenied = state.phonePermissionState == PermissionUiState.PERMANENTLY_DENIED
        val text =
            if (isPermanentlyDenied) {
                stringResource(id = R.string.phone_permission_permanent_denial)
            } else {
                stringResource(id = R.string.tracking_banner_phone_permission)
            }

        val actionText =
            if (isPermanentlyDenied) {
                stringResource(id = R.string.action_app_settings)
            } else {
                stringResource(id = R.string.action_grant)
            }

        val onAction =
            if (isPermanentlyDenied) {
                onOpenAppSettings
            } else {
                onGrantPhonePermission
            }

        TrackingBanner(
            text = text,
            actionText = actionText,
            onAction = onAction,
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun UsageAccessBanner(
    state: TrackingBannerState,
    onShowUsageAccessDialog: () -> Unit,
) {
    if (state.showUsageAccessBanner) {
        TrackingBanner(
            text = stringResource(id = R.string.tracking_banner_usage_access),
            actionText = stringResource(id = R.string.action_settings),
            onAction = onShowUsageAccessDialog,
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

@Composable
private fun TrafficErrorBanner(
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
private fun TrafficFallbackBanner(
    state: TrackingBannerState,
    onRetryTraffic: () -> Unit,
) {
    if (state.showTrafficFallbackBanner) {
        TrackingBanner(
            text = stringResource(id = R.string.tracking_banner_traffic_fallback, state.trafficFallbackSlot + 1),
            actionText = stringResource(id = R.string.action_retry),
            onAction = onRetryTraffic,
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

@Composable
private fun CallsErrorBanner(
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
private fun AppSearchErrorBanner(
    state: TrackingBannerState,
    onRetryAppSearch: () -> Unit,
) {
    if (state.showAppSearchErrorBanner) {
        TrackingBanner(
            text = stringResource(id = R.string.tracking_banner_appsearch_error),
            actionText = stringResource(id = R.string.action_retry),
            onAction = onRetryAppSearch,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ShortcutsErrorBanner(
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
private fun NotificationPermissionBanner(
    state: TrackingBannerState,
    onGrantNotificationPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    if (state.notificationPermissionState != PermissionUiState.GRANTED) {
        val isPermanentlyDenied = state.notificationPermissionState == PermissionUiState.PERMANENTLY_DENIED
        val text =
            if (isPermanentlyDenied) {
                stringResource(id = R.string.notification_permission_permanent_denial)
            } else {
                stringResource(id = R.string.tracking_banner_notification_permission)
            }

        val actionText =
            if (isPermanentlyDenied) {
                stringResource(id = R.string.action_app_settings)
            } else {
                stringResource(id = R.string.action_grant)
            }

        val onAction =
            if (isPermanentlyDenied) {
                onOpenAppSettings
            } else {
                onGrantNotificationPermission
            }

        TrackingBanner(
            text = text,
            actionText = actionText,
            onAction = onAction,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun WidgetErrorBanner(
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
private fun InaccurateCallsBanner(
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
private fun TrackingBanner(
    text: String,
    actionText: String?,
    onAction: () -> Unit,
    containerColor: Color,
    contentColor: Color,
) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (actionText != null) {
                TextButton(onClick = onAction) {
                    Text(text = actionText, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
