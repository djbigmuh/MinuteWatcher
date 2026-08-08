package com.example.minutewatcher.feature.settings.ui

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
import androidx.compose.ui.unit.dp
import com.example.minutewatcher.core.model.SimSettingsState
import com.example.minutewatcher.core.model.WidgetSettingsState
import com.example.minutewatcher.core.model.WidgetUiState
import com.example.minutewatcher.core.resources.R

@Composable
fun SimCardsSettingsSection(
    state: WidgetSettingsState,
    uiState: WidgetUiState?,
    enabled: Boolean = true,
    onUpdateSimSettings: (Int, (SimSettingsState) -> SimSettingsState) -> Unit,
) {
    SettingLabel(stringResource(id = R.string.sim_cards_title))
    val activeSims = uiState?.simUiStates ?: emptyList()
    if (activeSims.isEmpty()) {
        Text(
            text = stringResource(id = R.string.no_sim_found),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 16.dp),
        )
    } else {
        activeSims.forEach { simUi ->
            val simState = state.simStates[simUi.slot]
            if (simState != null) {
                SimSettingsSection(
                    slot = simUi.slot,
                    operatorName = simUi.operatorName,
                    sim = simState,
                    enabled = enabled,
                    onUpdate = { update -> onUpdateSimSettings(simUi.slot, update) },
                )
            }
        }
    }
}

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

@Composable
private fun SimDetailsSettings(
    sim: SimSettingsState,
    enabled: Boolean,
    onUpdate: ((SimSettingsState) -> SimSettingsState) -> Unit,
) {
    CheckboxSetting(
        label = stringResource(id = R.string.display_name),
        checked = sim.showName,
        enabled = enabled,
        onCheckedChange = { v -> onUpdate { it.copy(showName = v) } },
    )
    CheckboxSetting(
        label = stringResource(id = R.string.display_icon),
        checked = sim.showIcon,
        enabled = enabled,
        onCheckedChange = { v -> onUpdate { it.copy(showIcon = v) } },
    )
    CheckboxSetting(
        label = stringResource(id = R.string.display_minutes),
        checked = sim.showMinutes,
        enabled = enabled,
        onCheckedChange = { v -> onUpdate { it.copy(showMinutes = v) } },
    )
    CheckboxSetting(
        label = stringResource(id = R.string.display_minutes_limit),
        checked = sim.showMinutesLimit,
        enabled = enabled,
        onCheckedChange = { v -> onUpdate { it.copy(showMinutesLimit = v) } },
    )
    CheckboxSetting(
        label = stringResource(id = R.string.display_traffic),
        checked = sim.showTraffic,
        enabled = enabled,
        onCheckedChange = { v -> onUpdate { it.copy(showTraffic = v) } },
    )
    CheckboxSetting(
        label = stringResource(id = R.string.display_traffic_limit),
        checked = sim.showTrafficLimit,
        enabled = enabled,
        onCheckedChange = { v -> onUpdate { it.copy(showTrafficLimit = v) } },
    )
}
