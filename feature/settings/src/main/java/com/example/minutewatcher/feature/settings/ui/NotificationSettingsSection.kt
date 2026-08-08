package com.example.minutewatcher.feature.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.minutewatcher.core.model.WidgetSettingsState
import com.example.minutewatcher.core.resources.R

@Composable
fun NotificationSettingsSection(
    state: WidgetSettingsState,
    enabled: Boolean = true,
    onUpdateNotifyMinutes: (Boolean) -> Unit,
    onUpdateNotifyTraffic: (Boolean) -> Unit,
    onUpdateCallTracking: (Boolean) -> Unit,
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
        label = stringResource(id = R.string.call_tracking_title),
        checked = state.callTrackingEnabled,
        enabled = enabled,
    ) { onUpdateCallTracking(it) }
}
