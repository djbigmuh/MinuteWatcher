package com.example.minutewatcher.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.WidgetSettingsState
import com.example.minutewatcher.core.resources.R
import kotlin.math.roundToInt

@Composable
fun GeneralSettingsSection(
    state: WidgetSettingsState,
    enabled: Boolean = true,
    onUpdateLayoutMode: (Int) -> Unit,
    onUpdateSimArrangement: (Int) -> Unit,
    onUpdateTransparency: (Int) -> Unit,
    onUpdateFontSize: (Int) -> Unit,
    onShowColorPicker: () -> Unit,
) {
    LayoutModeSetting(state, enabled, onUpdateLayoutMode)

    SimArrangementSetting(state, enabled, onUpdateSimArrangement)

    Spacer(modifier = Modifier.height(16.dp))

    TransparencySetting(state, enabled, onUpdateTransparency)

    FontSizeSetting(state, enabled, onUpdateFontSize)

    BackgroundColorSetting(state, enabled, onShowColorPicker)
}

@Composable
private fun LayoutModeSetting(
    state: WidgetSettingsState,
    enabled: Boolean,
    onUpdateLayoutMode: (Int) -> Unit,
) {
    SettingLabel(stringResource(id = R.string.layout_mode_title))
    val layoutModes = stringArrayResource(id = R.array.layout_modes)
    var layoutExpanded by remember { mutableStateOf(value = false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { layoutExpanded = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
        ) {
            Text(text = layoutModes.getOrNull(state.layoutMode).orEmpty())
        }
        DropdownMenu(expanded = layoutExpanded && enabled, onDismissRequest = { layoutExpanded = false }) {
            layoutModes.forEachIndexed { index, name ->
                DropdownMenuItem(
                    text = { Text(text = name) },
                    onClick = {
                        onUpdateLayoutMode(index)
                        layoutExpanded = false
                    },
                    enabled = enabled,
                )
            }
        }
    }
}

@Composable
private fun SimArrangementSetting(
    state: WidgetSettingsState,
    enabled: Boolean,
    onUpdateSimArrangement: (Int) -> Unit,
) {
    SettingLabel(stringResource(id = R.string.sim_arrangement_prompt))
    val arrangements = stringArrayResource(id = R.array.sim_arrangements)
    OutlinedButton(
        onClick = {
            val next =
                if (state.simArrangement == Constants.Widget.SIM_ARRANGEMENT_HORIZONTAL) {
                    Constants.Widget.SIM_ARRANGEMENT_VERTICAL
                } else {
                    Constants.Widget.SIM_ARRANGEMENT_HORIZONTAL
                }
            onUpdateSimArrangement(next)
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
    ) {
        Text(
            text = arrangements.getOrNull(state.simArrangement).orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun TransparencySetting(
    state: WidgetSettingsState,
    enabled: Boolean,
    onUpdateTransparency: (Int) -> Unit,
) {
    SettingLabel(stringResource(id = R.string.background_transparency))
    Slider(
        value = state.transparency.toFloat(),
        onValueChange = { onUpdateTransparency(it.roundToInt()) },
        valueRange = 0f..100f,
        steps = 99,
        enabled = enabled,
    )
}

@Composable
private fun FontSizeSetting(
    state: WidgetSettingsState,
    enabled: Boolean,
    onUpdateFontSize: (Int) -> Unit,
) {
    SettingLabel(stringResource(id = R.string.font_size))
    Slider(
        value = state.fontSize.toFloat(),
        onValueChange = { onUpdateFontSize(it.roundToInt()) },
        valueRange = 0f..4f,
        steps = 3,
        enabled = enabled,
    )
}

@Composable
private fun BackgroundColorSetting(
    state: WidgetSettingsState,
    enabled: Boolean,
    onShowColorPicker: () -> Unit,
) {
    SettingLabel(stringResource(id = R.string.color_background))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) {
                    onShowColorPicker()
                }.padding(vertical = 8.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .background(
                        color = Color(state.backgroundColor).copy(alpha = if (enabled) 1f else 0.5f),
                        shape = RoundedCornerShape(4.dp),
                    ),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = stringResource(id = R.string.custom_color),
            color = enabledContentColor(enabled),
        )
    }
}
