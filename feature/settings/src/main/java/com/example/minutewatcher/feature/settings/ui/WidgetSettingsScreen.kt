package com.example.minutewatcher.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.minutewatcher.core.model.R
import com.example.minutewatcher.core.model.SimSettingsState
import com.example.minutewatcher.feature.settings.ui.components.WidgetPreview
import com.skydoves.colorpickerview.ColorPickerDialog
import com.skydoves.colorpickerview.listeners.ColorEnvelopeListener

@Suppress("ktlint:standard:function-naming")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetSettingsScreen(
    viewModel: WidgetSettingsViewModel,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.settingsState.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val loadError by viewModel.loadError.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()

    if (loadError) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(id = R.string.error),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { viewModel.retryLoad() }) {
                    Text(text = stringResource(id = R.string.action_retry))
                }
            }
        }
        return
    }

    if (state == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val nonNullState = state!!

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.settings_widget_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        bottomBar = {
            Column(
                modifier =
                    Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .navigationBarsPadding()
                        .padding(16.dp),
            ) {
                Text(
                    text = stringResource(id = R.string.preview),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    uiState?.let {
                        WidgetPreview(
                            uiState = it,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onSave,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(id = R.string.save_button))
                }
            }
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .padding(paddingValues)
                    .verticalScroll(scrollState)
                    .padding(16.dp),
        ) {
            // Layout Mode
            SettingLabel(stringResource(id = R.string.layout_mode_title))
            val layoutModes = stringArrayResource(id = R.array.layout_modes)
            var layoutExpanded by remember { mutableStateOf(value = false) }
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { layoutExpanded = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = layoutModes.getOrNull(nonNullState.layoutMode) ?: "")
                }
                DropdownMenu(expanded = layoutExpanded, onDismissRequest = { layoutExpanded = false }) {
                    layoutModes.forEachIndexed { index, name ->
                        DropdownMenuItem(
                            text = { Text(text = name) },
                            onClick = {
                                viewModel.updateLayoutMode(index)
                                layoutExpanded = false
                            },
                        )
                    }
                }
            }

            // SIM Arrangement
            SettingLabel(stringResource(id = R.string.sim_arrangement_prompt))
            val arrangements = stringArrayResource(id = R.array.sim_arrangements)
            OutlinedButton(
                onClick = {
                    val next = if (nonNullState.simArrangement == 0) 1 else 0
                    viewModel.updateSimArrangement(next)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = arrangements.getOrNull(nonNullState.simArrangement) ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Transparency
            SettingLabel(stringResource(id = R.string.background_transparency))
            Slider(
                value = nonNullState.transparency.toFloat(),
                onValueChange = { viewModel.updateTransparency(it.toInt()) },
                valueRange = 0f..100f,
                steps = 99,
            )

            // Font Size
            SettingLabel(stringResource(id = R.string.font_size))
            Slider(
                value = nonNullState.fontSize.toFloat(),
                onValueChange = { viewModel.updateFontSize(it.toInt()) },
                valueRange = 0f..4f,
                steps = 3,
            )

            // Background Color
            SettingLabel(stringResource(id = R.string.color_background))
            val context = LocalContext.current
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            ColorPickerDialog
                                .Builder(context)
                                .setTitle(R.string.color_background)
                                .setPositiveButton(
                                    R.string.save_button,
                                    ColorEnvelopeListener { envelope, _ ->
                                        viewModel.updateBackgroundColor(envelope.color)
                                    },
                                ).setNegativeButton(R.string.cancel_button, null)
                                .attachAlphaSlideBar(false)
                                .attachBrightnessSlideBar(true)
                                .show()
                        }.padding(vertical = 8.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(40.dp)
                            .background(Color(nonNullState.backgroundColor), RoundedCornerShape(4.dp)),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(stringResource(id = R.string.custom_color))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Notifications
            SettingLabel(stringResource(id = R.string.notifications_title))
            CheckboxSetting(
                label = stringResource(id = R.string.notify_low_minutes_title),
                checked = nonNullState.notifyLowMinutes,
            ) { viewModel.updateNotifyMinutes(enabled = it) }
            CheckboxSetting(
                label = stringResource(id = R.string.notify_low_traffic_title),
                checked = nonNullState.notifyLowTraffic,
            ) { viewModel.updateNotifyTraffic(enabled = it) }
            CheckboxSetting(
                label = stringResource(id = R.string.call_tracking_title),
                checked = nonNullState.callTrackingEnabled,
            ) { viewModel.updateCallTracking(enabled = it) }

            Spacer(modifier = Modifier.height(16.dp))

            // SIM Settings
            nonNullState.simStates.forEachIndexed { index, sim ->
                SimSettingsSection(
                    index = index,
                    sim = sim,
                    onUpdate = { update -> viewModel.updateSimSettings(index, update) },
                )
            }
        }
    }
}

@Composable
fun SettingLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
fun CheckboxSetting(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = { onCheckedChange(!checked) })
                .padding(vertical = 4.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
    }
}

@Composable
fun SimSettingsSection(
    index: Int,
    sim: SimSettingsState,
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
                text = stringResource(id = R.string.sim_card_generic, index + 1),
                style = MaterialTheme.typography.titleSmall,
            )

            CheckboxSetting(
                label = stringResource(id = R.string.show_sim_checkbox),
                checked = sim.showSim,
                onCheckedChange = { v -> onUpdate { it.copy(showSim = v) } },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            CheckboxSetting(
                label = stringResource(id = R.string.display_name),
                checked = sim.showName,
                enabled = sim.showSim,
                onCheckedChange = { v -> onUpdate { it.copy(showName = v) } },
            )
            CheckboxSetting(
                label = stringResource(id = R.string.display_icon),
                checked = sim.showIcon,
                enabled = sim.showSim,
                onCheckedChange = { v -> onUpdate { it.copy(showIcon = v) } },
            )
            CheckboxSetting(
                label = stringResource(id = R.string.display_minutes),
                checked = sim.showMinutes,
                enabled = sim.showSim,
                onCheckedChange = { v -> onUpdate { it.copy(showMinutes = v) } },
            )
            CheckboxSetting(
                label = stringResource(id = R.string.display_minutes_limit),
                checked = sim.showMinutesLimit,
                enabled = sim.showSim,
                onCheckedChange = { v -> onUpdate { it.copy(showMinutesLimit = v) } },
            )
            CheckboxSetting(
                label = stringResource(id = R.string.display_traffic),
                checked = sim.showTraffic,
                enabled = sim.showSim,
                onCheckedChange = { v -> onUpdate { it.copy(showTraffic = v) } },
            )
            CheckboxSetting(
                label = stringResource(id = R.string.display_traffic_limit),
                checked = sim.showTrafficLimit,
                enabled = sim.showSim,
                onCheckedChange = { v -> onUpdate { it.copy(showTrafficLimit = v) } },
            )
        }
    }
}
