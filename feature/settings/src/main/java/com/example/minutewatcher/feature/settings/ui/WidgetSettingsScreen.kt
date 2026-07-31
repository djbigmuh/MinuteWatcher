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
import androidx.compose.runtime.DisposableEffect
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
import com.example.minutewatcher.core.model.SimSettingsState
import com.example.minutewatcher.core.model.WidgetSettingsState
import com.example.minutewatcher.core.model.WidgetUiState
import com.example.minutewatcher.core.resources.R
import com.example.minutewatcher.feature.settings.ui.components.WidgetPreview
import com.skydoves.colorpickerview.ColorPickerDialog
import com.skydoves.colorpickerview.listeners.ColorEnvelopeListener

@Composable
fun WidgetSettingsScreen(
    viewModel: WidgetSettingsViewModel,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.settingsState.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val loadError by viewModel.loadError.collectAsStateWithLifecycle()

    if (loadError) {
        ErrorState(onRetry = { viewModel.retryLoad() })
        return
    }

    val nonNullState = state
    if (nonNullState == null) {
        LoadingState()
        return
    }

    WidgetSettingsContent(
        state = nonNullState,
        uiState = uiState,
        viewModel = viewModel,
        onSave = onSave,
        onBack = onBack,
    )
}

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorState(onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(id = R.string.error),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRetry) {
                Text(text = stringResource(id = R.string.action_retry))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WidgetSettingsContent(
    state: WidgetSettingsState,
    uiState: WidgetUiState?,
    viewModel: WidgetSettingsViewModel,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    val scrollState = rememberScrollState()
    var showColorPicker by remember { mutableStateOf(false) }

    if (showColorPicker) {
        ColorPickerWrapper(
            onColorSelected = { viewModel.updateBackgroundColor(it) },
            onDismiss = { showColorPicker = false },
        )
    }

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
            BottomPreviewBar(
                uiState = uiState,
                onSave = onSave,
            )
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .padding(paddingValues)
                    .verticalScroll(scrollState)
                    .padding(16.dp),
        ) {
            GeneralSettingsSection(
                state = state,
                onUpdateLayoutMode = viewModel::updateLayoutMode,
                onUpdateSimArrangement = viewModel::updateSimArrangement,
                onUpdateTransparency = viewModel::updateTransparency,
                onUpdateFontSize = viewModel::updateFontSize,
                onShowColorPicker = { showColorPicker = true },
            )

            Spacer(modifier = Modifier.height(16.dp))

            NotificationSettingsSection(
                state = state,
                onUpdateNotifyMinutes = viewModel::updateNotifyMinutes,
                onUpdateNotifyTraffic = viewModel::updateNotifyTraffic,
                onUpdateCallTracking = viewModel::updateCallTracking,
            )

            Spacer(modifier = Modifier.height(16.dp))

            SimCardsSettingsSection(
                state = state,
                uiState = uiState,
                onUpdateSimSettings = viewModel::updateSimSettings,
            )
        }
    }
}

@Composable
private fun ColorPickerWrapper(
    onColorSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val dialog =
            ColorPickerDialog
                .Builder(context)
                .setTitle(R.string.color_background)
                .setPositiveButton(
                    R.string.save_button,
                    ColorEnvelopeListener { envelope, _ ->
                        onColorSelected(envelope.color)
                        onDismiss()
                    },
                ).setNegativeButton(R.string.cancel_button) { dialogInterface, _ ->
                    dialogInterface.dismiss()
                    onDismiss()
                }.attachAlphaSlideBar(false)
                .attachBrightnessSlideBar(true)
                .setOnDismissListener {
                    onDismiss()
                }.show()

        onDispose {
            dialog.dismiss()
        }
    }
}

@Composable
private fun BottomPreviewBar(
    uiState: WidgetUiState?,
    onSave: () -> Unit,
) {
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
}

@Composable
private fun GeneralSettingsSection(
    state: WidgetSettingsState,
    onUpdateLayoutMode: (Int) -> Unit,
    onUpdateSimArrangement: (Int) -> Unit,
    onUpdateTransparency: (Int) -> Unit,
    onUpdateFontSize: (Int) -> Unit,
    onShowColorPicker: () -> Unit,
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
            Text(text = layoutModes.getOrNull(state.layoutMode) ?: "")
        }
        DropdownMenu(expanded = layoutExpanded, onDismissRequest = { layoutExpanded = false }) {
            layoutModes.forEachIndexed { index, name ->
                DropdownMenuItem(
                    text = { Text(text = name) },
                    onClick = {
                        onUpdateLayoutMode(index)
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
            val next = if (state.simArrangement == 0) 1 else 0
            onUpdateSimArrangement(next)
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = arrangements.getOrNull(state.simArrangement) ?: "",
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Transparency
    SettingLabel(stringResource(id = R.string.background_transparency))
    Slider(
        value = state.transparency.toFloat(),
        onValueChange = { onUpdateTransparency(it.toInt()) },
        valueRange = 0f..100f,
        steps = 99,
    )

    // Font Size
    SettingLabel(stringResource(id = R.string.font_size))
    Slider(
        value = state.fontSize.toFloat(),
        onValueChange = { onUpdateFontSize(it.toInt()) },
        valueRange = 0f..4f,
        steps = 3,
    )

    // Background Color
    SettingLabel(stringResource(id = R.string.color_background))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable {
                    onShowColorPicker()
                }.padding(vertical = 8.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .background(Color(state.backgroundColor), RoundedCornerShape(4.dp)),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(stringResource(id = R.string.custom_color))
    }
}

@Composable
private fun NotificationSettingsSection(
    state: WidgetSettingsState,
    onUpdateNotifyMinutes: (Boolean) -> Unit,
    onUpdateNotifyTraffic: (Boolean) -> Unit,
    onUpdateCallTracking: (Boolean) -> Unit,
) {
    SettingLabel(stringResource(id = R.string.notifications_title))
    CheckboxSetting(
        label = stringResource(id = R.string.notify_low_minutes_title),
        checked = state.notifyLowMinutes,
    ) { onUpdateNotifyMinutes(it) }
    CheckboxSetting(
        label = stringResource(id = R.string.notify_low_traffic_title),
        checked = state.notifyLowTraffic,
    ) { onUpdateNotifyTraffic(it) }
    CheckboxSetting(
        label = stringResource(id = R.string.call_tracking_title),
        checked = state.callTrackingEnabled,
    ) { onUpdateCallTracking(it) }
}

@Composable
private fun SimCardsSettingsSection(
    state: WidgetSettingsState,
    uiState: WidgetUiState?,
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
                    onUpdate = { update -> onUpdateSimSettings(simUi.slot, update) },
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
            color =
                if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
        )
    }
}

@Composable
fun SimSettingsSection(
    slot: Int,
    operatorName: String,
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
                text = "${stringResource(id = R.string.sim_card_generic, slot + 1)} ($operatorName)",
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
