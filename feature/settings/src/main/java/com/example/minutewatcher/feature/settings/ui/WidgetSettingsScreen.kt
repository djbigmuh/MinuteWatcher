package com.example.minutewatcher.feature.settings.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.minutewatcher.core.model.WidgetSettingsState
import com.example.minutewatcher.core.model.WidgetUiState
import com.example.minutewatcher.core.resources.R

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
            onColorSelected = { color -> viewModel.updateSettings { it.copy(backgroundColor = color) } },
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
                onUpdateLayoutMode = { mode -> viewModel.updateSettings { it.copy(layoutMode = mode) } },
                onUpdateSimArrangement = { arr -> viewModel.updateSettings { it.copy(simArrangement = arr) } },
                onUpdateTransparency = { tr -> viewModel.updateSettings { it.copy(transparency = tr) } },
                onUpdateFontSize = { fs -> viewModel.updateSettings { it.copy(fontSize = fs) } },
                onShowColorPicker = { showColorPicker = true },
            )

            Spacer(modifier = Modifier.height(16.dp))

            NotificationSettingsSection(
                state = state,
                onUpdateNotifyMinutes = { en -> viewModel.updateSettings { it.copy(notifyLowMinutes = en) } },
                onUpdateNotifyTraffic = { en -> viewModel.updateSettings { it.copy(notifyLowTraffic = en) } },
                onUpdateCallTracking = { en -> viewModel.updateSettings { it.copy(callTrackingEnabled = en) } },
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
