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
import com.example.minutewatcher.core.model.R
import com.example.minutewatcher.feature.main.TrackingBannerState

@Composable
fun TrackingBanners(
    state: TrackingBannerState,
    onGrantPhonePermission: () -> Unit,
    onOpenUsageSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onRetryAppSearch: () -> Unit,
    onRetryTraffic: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (state.showPhonePermissionBanner) {
            val text =
                if (state.isPhonePermissionPermanentlyDenied) {
                    stringResource(id = R.string.phone_permission_permanent_denial)
                } else {
                    stringResource(id = R.string.tracking_banner_phone_permission)
                }

            val actionText =
                if (state.isPhonePermissionPermanentlyDenied) {
                    stringResource(id = R.string.action_app_settings)
                } else {
                    stringResource(id = R.string.action_grant)
                }

            val onAction =
                if (state.isPhonePermissionPermanentlyDenied) {
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
        if (state.showUsageAccessBanner) {
            TrackingBanner(
                text = stringResource(id = R.string.tracking_banner_usage_access),
                actionText = stringResource(id = R.string.action_settings),
                onAction = onOpenUsageSettings,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
        if (state.showTrafficErrorBanner) {
            TrackingBanner(
                text = stringResource(id = R.string.tracking_banner_traffic_error),
                actionText = stringResource(id = R.string.action_retry),
                onAction = onRetryTraffic,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
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
