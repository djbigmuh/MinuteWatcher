package com.example.minutewatcher.feature.settings.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.minutewatcher.core.model.WidgetSettingsState
import com.example.minutewatcher.core.resources.R

@Composable
fun NotificationSettingsSection(
    state: WidgetSettingsState,
    enabled: Boolean = true,
    aodSummaryAvailable: Boolean = true,
    aodSummaryHasContent: Boolean = true,
    onUpdateNotifyMinutes: (Boolean) -> Unit,
    onUpdateNotifyTraffic: (Boolean) -> Unit,
    onUpdateAodSummary: (Boolean) -> Unit = {},
) {
    SettingLabel(stringResource(id = R.string.notifications_title))
    CheckboxSetting(
        label = stringResource(id = R.string.notify_low_minutes_title),
        checked = state.notifyLowMinutes,
        enabled = enabled,
    ) { onUpdateNotifyMinutes(it) }
    CheckboxSetting(
        label = stringResource(id = R.string.notify_low_traffic_title),
        checked = state.notifyLowTraffic,
        enabled = enabled,
    ) { onUpdateNotifyTraffic(it) }
    CheckboxSetting(
        label = stringResource(id = R.string.aod_summary_title),
        checked = state.aodSummaryEnabled,
        enabled = enabled && aodSummaryAvailable,
    ) { onUpdateAodSummary(it) }
    Text(
        text = stringResource(id = R.string.aod_summary_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
    if (!aodSummaryAvailable) {
        Text(
            text = stringResource(id = R.string.aod_summary_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    } else if (state.aodSummaryEnabled && !aodSummaryHasContent) {
        Text(
            text = stringResource(id = R.string.aod_summary_no_limits_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
