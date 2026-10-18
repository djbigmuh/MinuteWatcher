package host.serv.djbigmuh.minutewatcher.feature.settings.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import host.serv.djbigmuh.minutewatcher.core.model.WidgetSettingsState
import host.serv.djbigmuh.minutewatcher.core.resources.R

@Composable
fun TrackingSettingsSection(
    state: WidgetSettingsState,
    enabled: Boolean = true,
    onUpdateCallTracking: (Boolean) -> Unit,
    onUpdateAssistantAccess: (Boolean) -> Unit,
) {
    SettingLabel(stringResource(id = R.string.tracking_title))
    CheckboxSetting(
        label = stringResource(id = R.string.call_tracking_title),
        checked = state.callTrackingEnabled,
        enabled = enabled,
    ) { onUpdateCallTracking(it) }
    Text(
        text = stringResource(id = R.string.call_tracking_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
    Spacer(modifier = Modifier.height(8.dp))
    CheckboxSetting(
        label = stringResource(id = R.string.assistant_access_title),
        checked = state.assistantAccessEnabled,
        enabled = enabled,
    ) { onUpdateAssistantAccess(it) }
    Text(
        text = stringResource(id = R.string.assistant_access_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
}
