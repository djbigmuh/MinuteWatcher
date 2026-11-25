package com.github.djbigmuh.minutewatcher.feature.settings.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.djbigmuh.minutewatcher.core.model.SimSettingsState
import com.github.djbigmuh.minutewatcher.core.model.WidgetSettingsState
import com.github.djbigmuh.minutewatcher.core.model.WidgetUiState
import com.github.djbigmuh.minutewatcher.core.resources.R

private val SIM_CARD_VERTICAL_PADDING = 8.dp
private val SIM_CARD_CONTENT_PADDING = 12.dp
private val SIM_HEADER_MIN_HEIGHT = 48.dp
private val SIM_HEADER_ICON_SIZE = 24.dp
private val SIM_HEADER_SPACING = 12.dp
private val SIM_DIVIDER_VERTICAL_PADDING = 4.dp
private val SIM_DETAILS_INDENT_LEVEL_1 = 24.dp
private val SIM_DETAILS_INDENT_LEVEL_2 = 48.dp
private val SIM_UNAVAILABLE_PADDING = 8.dp
private val SIM_NO_SIM_PADDING = 16.dp

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
        // Editing lock prevents silent data loss.
        Text(
            text = stringResource(id = R.string.sim_list_unavailable_error),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(vertical = SIM_UNAVAILABLE_PADDING),
        )
        return
    }

    val activeSims = uiState?.simUiStates.orEmpty()
    if (activeSims.isEmpty()) {
        Text(
            text = stringResource(id = R.string.no_sim_found),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = SIM_NO_SIM_PADDING),
        )
    } else {
        activeSims.forEach { simUi ->
            key(simUi.slot) {
                val simState = state.simStates[simUi.slot] ?: SimSettingsState()
                SimSettingsSection(
                    slot = simUi.slot,
                    operatorName = simUi.operatorName,
                    sim = simState,
                    enabled = true,
                    operatorIconRes = simUi.operatorIconRes,
                ) { update ->
                    onUpdateSimSettings(simUi.slot, update)
                }
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
    operatorIconRes: Int = 0,
    onUpdate: ((SimSettingsState) -> SimSettingsState) -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = SIM_CARD_VERTICAL_PADDING),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(SIM_CARD_CONTENT_PADDING)) {
            SimHeaderRow(
                slot = slot,
                operatorName = operatorName,
                operatorIconRes = operatorIconRes,
                showSim = sim.showSim,
                enabled = enabled,
            ) { v -> onUpdate { it.copy(showSim = v) } }

            if (sim.showSim) {
                HorizontalDivider(modifier = Modifier.padding(vertical = SIM_DIVIDER_VERTICAL_PADDING))

                SimDetailsSettings(
                    sim = sim,
                    enabled = enabled,
                    onUpdate = onUpdate,
                )
            }
        }
    }
}

@Composable
private fun SimHeaderRow(
    slot: Int,
    operatorName: String,
    operatorIconRes: Int,
    showSim: Boolean,
    enabled: Boolean,
    onToggleShowSim: (Boolean) -> Unit,
) {
    val simTitle = "${stringResource(id = R.string.sim_card_generic, slot + 1)} ($operatorName)"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = SIM_HEADER_MIN_HEIGHT)
                .toggleable(
                    value = showSim,
                    enabled = enabled,
                    role = Role.Checkbox,
                    onValueChange = onToggleShowSim,
                )
                .padding(vertical = 4.dp),
    ) {
        if (operatorIconRes != 0) {
            Image(
                painter = painterResource(id = operatorIconRes),
                contentDescription = null,
                modifier = Modifier.size(SIM_HEADER_ICON_SIZE),
            )
            Spacer(modifier = Modifier.width(SIM_HEADER_SPACING))
        }
        Text(
            text = simTitle,
            style = MaterialTheme.typography.titleSmall,
            color = enabledContentColor(enabled),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(SIM_HEADER_SPACING))
        Checkbox(
            checked = showSim,
            onCheckedChange = null,
            enabled = enabled,
        )
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
        modifier = Modifier.padding(start = SIM_DETAILS_INDENT_LEVEL_1),
        enabled = enabled,
        onCheckedChange = { v -> onUpdate { it.copy(showName = v) } },
    )
    CheckboxSetting(
        label = stringResource(id = R.string.display_icon),
        checked = sim.showIcon,
        modifier = Modifier.padding(start = SIM_DETAILS_INDENT_LEVEL_1),
        enabled = enabled,
        onCheckedChange = { v -> onUpdate { it.copy(showIcon = v) } },
    )
    CheckboxSetting(
        label = stringResource(id = R.string.display_minutes),
        checked = sim.showMinutes,
        modifier = Modifier.padding(start = SIM_DETAILS_INDENT_LEVEL_1),
        enabled = enabled,
        onCheckedChange = { v ->
            onUpdate {
                it.copy(
                    showMinutes = v,
                    showMinutesLimit = v && it.showMinutesLimit,
                )
            }
        },
    )
    CheckboxSetting(
        label = stringResource(id = R.string.display_minutes_limit),
        checked = sim.showMinutesLimit,
        modifier = Modifier.padding(start = SIM_DETAILS_INDENT_LEVEL_2),
        enabled = enabled && sim.showMinutes,
        onCheckedChange = { v -> onUpdate { it.copy(showMinutesLimit = v) } },
    )
    CheckboxSetting(
        label = stringResource(id = R.string.display_traffic),
        checked = sim.showTraffic,
        modifier = Modifier.padding(start = SIM_DETAILS_INDENT_LEVEL_1),
        enabled = enabled,
        onCheckedChange = { v ->
            onUpdate {
                it.copy(
                    showTraffic = v,
                    showTrafficLimit = v && it.showTrafficLimit,
                )
            }
        },
    )
    CheckboxSetting(
        label = stringResource(id = R.string.display_traffic_limit),
        checked = sim.showTrafficLimit,
        modifier = Modifier.padding(start = SIM_DETAILS_INDENT_LEVEL_2),
        enabled = enabled && sim.showTraffic,
        onCheckedChange = { v -> onUpdate { it.copy(showTrafficLimit = v) } },
    )
}
