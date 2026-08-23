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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.minutewatcher.core.android.util.WidgetStringsFactory
import com.example.minutewatcher.core.android.util.WidgetUiMapper
import com.example.minutewatcher.core.model.WidgetSettingsState
import com.example.minutewatcher.core.model.WidgetUiState
import com.example.minutewatcher.core.resources.R

@Composable
fun WidgetSettingsScreen(
    viewModel: WidgetSettingsViewModel,
    onSave: suspend () -> Unit,
    onBack: () -> Unit,
) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val strings = remember(configuration) { WidgetStringsFactory.create(context) }

    val screenUiState =
        remember(uiState.settings, snapshot, strings) {
            val settings = uiState.settings ?: return@remember null
            val operatorNames = WidgetStringsFactory.createOperatorNames(context, snapshot.simSnapshots)
            WidgetUiMapper.mapFromSnapshot(
                strings = strings,
                snapshot = snapshot,
                state = settings,
                operatorNames = operatorNames,
            )
        }

    if (uiState.isLoadError) {
        ErrorState(
            onRetry = { viewModel.retryLoad() },
            onBack = onBack,
        )
        return
    }

    val settings = uiState.settings
    if (settings == null) {
        LoadingState()
        return
    }

    WidgetSettingsContent(
        settings = settings,
        screenUiState = screenUiState,
        isSaving = uiState.isSaving,
        saveResult = uiState.saveResult,
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
private fun ErrorState(
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text(stringResource(id = R.string.settings_widget_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WidgetSettingsContent(
    settings: WidgetSettingsState,
    screenUiState: WidgetUiState?,
    isSaving: Boolean,
    saveResult: SaveResult?,
    viewModel: WidgetSettingsViewModel,
    onSave: suspend () -> Unit,
    onBack: () -> Unit,
) {
    var showColorPicker by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val successMessage = stringResource(id = R.string.settings_saved_toast)
    val partialSuccessMessage = stringResource(id = R.string.settings_save_partial_success)
    val failedMessage = stringResource(id = R.string.settings_save_failed)

    LaunchedEffect(saveResult) {
        saveResult?.let { result ->
            val message =
                when (result) {
                    SaveResult.Success -> successMessage
                    is SaveResult.PartialSuccess -> partialSuccessMessage
                    is SaveResult.PartialFailure -> result.message
                    SaveResult.Failed -> failedMessage
                }

            snackbarHostState.showSnackbar(message)
            viewModel.consumeSaveResult()
        }
    }

    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text(stringResource(id = R.string.settings_widget_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            BottomPreviewBar(
                uiState = screenUiState,
                isSaving = isSaving,
                onSave = onSave,
            )
        },
    ) { paddingValues ->
        WidgetSettingsList(
            modifier = Modifier.padding(paddingValues),
            state = settings,
            uiState = screenUiState,
            viewModel = viewModel,
            onShowColorPicker = { showColorPicker = true },
        )
    }
}

@Composable
private fun WidgetSettingsList(
    modifier: Modifier = Modifier,
    state: WidgetSettingsState,
    uiState: WidgetUiState?,
    viewModel: WidgetSettingsViewModel,
    onShowColorPicker: () -> Unit,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier =
            modifier
                .verticalScroll(scrollState)
                .padding(16.dp),
    ) {
        GeneralSettingsSection(
            state = state,
            enabled = true,
            onUpdateLayoutMode = { mode -> viewModel.updateSettings { it.copy(layoutMode = mode) } },
            onUpdateSimArrangement = { arr -> viewModel.updateSettings { it.copy(simArrangement = arr) } },
            onUpdateTransparency = { tr -> viewModel.updateSettings { it.copy(transparency = tr) } },
            onUpdateFontSize = { fs -> viewModel.updateSettings { it.copy(fontSize = fs) } },
            onShowColorPicker = onShowColorPicker,
        )

        Spacer(modifier = Modifier.height(16.dp))

        NotificationSettingsSection(
            state = state,
            enabled = true,
            onUpdateNotifyMinutes = { en -> viewModel.updateSettings { it.copy(notifyLowMinutes = en) } },
            onUpdateNotifyTraffic = { en -> viewModel.updateSettings { it.copy(notifyLowTraffic = en) } },
        )

        Spacer(modifier = Modifier.height(16.dp))

        SimCardsSettingsSection(
            state = state,
            uiState = uiState,
            enabled = !state.simListLoadFailed,
            onUpdateSimSettings = viewModel::updateSimSettings,
        )
    }
}
