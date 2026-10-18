package host.serv.djbigmuh.minutewatcher.feature.main.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import host.serv.djbigmuh.minutewatcher.core.model.PermissionUiState
import host.serv.djbigmuh.minutewatcher.core.resources.R
import host.serv.djbigmuh.minutewatcher.feature.main.TrackingBannerState

@Composable
internal fun PhonePermissionBanner(
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
internal fun UsageAccessBanner(
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
internal fun NotificationPermissionBanner(
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
internal fun TrackingBanner(
    text: String,
    actionText: String?,
    onAction: () -> Unit,
    containerColor: Color,
    contentColor: Color,
    onDismiss: (() -> Unit)? = null,
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
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (actionText != null) {
                    TextButton(onClick = onAction) {
                        Text(text = actionText, style = MaterialTheme.typography.labelLarge)
                    }
                }
                if (onDismiss != null) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(id = R.string.banner_dismiss_content_desc),
                            modifier = Modifier.padding(4.dp),
                        )
                    }
                }
            }
        }
    }
}
