package host.serv.djbigmuh.minutewatcher.feature.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import host.serv.djbigmuh.minutewatcher.core.model.SimSettingsState
import host.serv.djbigmuh.minutewatcher.core.model.WidgetSettingsState
import host.serv.djbigmuh.minutewatcher.core.model.WidgetUiState
import host.serv.djbigmuh.minutewatcher.core.resources.R

@Composable
fun SimCardsSettingsSection(
    state: WidgetSettingsState,
    uiState: WidgetUiState?,
    enabled: Boolean = true,
    onUpdateSimSettings: (Int, (SimSettingsState) -> SimSettingsState) -> Unit,
) {
    SettingLabel(stringResource(id = R.string.sim_cards_title))

    if (!enabled) {
        // Strict API compliance: observeActiveSims() on AOSP completes in milliseconds.
        // Timeout here indicates storage degradation on custom firmware.
        // Editing lock prevents silent data loss.
        // This is a custom firmware issue, not a project issue.
        Text(
            text = stringResource(id = R.string.sim_list_unavailable_error),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        return
    }

    val activeSims = uiState?.simUiStates.orEmpty()
    if (activeSims.isEmpty()) {
        Text(
            text = stringResource(id = R.string.no_sim_found),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 16.dp),
        )
    } else {
        activeSims.forEach { simUi ->
            val simState = state.simStates[simUi.slot] ?: SimSettingsState()
            SimSettingsSection(
                slot = simUi.slot,
                operatorName = simUi.operatorName,
                sim = simState,
                enabled = true,
            ) { update ->
                onUpdateSimSettings(simUi.slot, update)
            }
        }
    }
}

/**
 * Composable for a single SIM card settings section.
 */
@Composable
fun SimSettingsSection(
    slot: Int,
    operatorName: String,
    sim: SimSettingsState,
    enabled: Boolean,
    onUpdate: ((SimSettingsState) -> SimSettingsState) -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = "${stringResource(id = R.string.sim_card_generic, slot + 1)} ($operatorName)",
                style = MaterialTheme.typography.titleSmall,
                color = enabledContentColor(enabled),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            CheckboxSetting(
                label = stringResource(id = R.string.show_sim_checkbox),
                checked = sim.showSim,
                enabled = enabled,
                onCheckedChange = { v -> onUpdate { it.copy(showSim = v) } },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            SimDetailsSettings(
                sim = sim,
                enabled = enabled && sim.showSim,
                onUpdate = onUpdate,
            )
        }
    }
}

/**
 * Composable for detailed SIM card settings.
 */
@Composable
private fun SimDetailsSettings(
    sim: SimSettingsState,
    enabled: Boolean,
    onUpdate: ((SimSettingsState) -> SimSettingsState) -> Unit,
) {
    CheckboxSetting(
        label = stringResource(id = R.string.display_name),
        checked = sim.showName,
        modifier = Modifier.padding(start = 24.dp),
        enabled = enabled,
        onCheckedChange = { v -> onUpdate { it.copy(showName = v) } },
    )
    CheckboxSetting(
        label = stringResource(id = R.string.display_icon),
        checked = sim.showIcon,
        modifier = Modifier.padding(start = 24.dp),
        enabled = enabled,
        onCheckedChange = { v -> onUpdate { it.copy(showIcon = v) } },
    )
    CheckboxSetting(
        label = stringResource(id = R.string.display_minutes),
        checked = sim.showMinutes,
        modifier = Modifier.padding(start = 24.dp),
        enabled = enabled,
        onCheckedChange = { v ->
            onUpdate {
                it.copy(
                    showMinutes = v,
                    showMinutesLimit = if (!v) false else it.showMinutesLimit,
                )
            }
        },
    )
    CheckboxSetting(
        label = stringResource(id = R.string.display_minutes_limit),
        checked = sim.showMinutesLimit,
        modifier = Modifier.padding(start = 48.dp),
        enabled = enabled && sim.showMinutes,
        onCheckedChange = { v -> onUpdate { it.copy(showMinutesLimit = v) } },
    )
    CheckboxSetting(
        label = stringResource(id = R.string.display_traffic),
        checked = sim.showTraffic,
        modifier = Modifier.padding(start = 24.dp),
        enabled = enabled,
        onCheckedChange = { v ->
            onUpdate {
                it.copy(
                    showTraffic = v,
                    showTrafficLimit = if (!v) false else it.showTrafficLimit,
                )
            }
        },
    )
    CheckboxSetting(
        label = stringResource(id = R.string.display_traffic_limit),
        checked = sim.showTrafficLimit,
        modifier = Modifier.padding(start = 48.dp),
        enabled = enabled && sim.showTraffic,
        onCheckedChange = { v -> onUpdate { it.copy(showTrafficLimit = v) } },
    )
}
