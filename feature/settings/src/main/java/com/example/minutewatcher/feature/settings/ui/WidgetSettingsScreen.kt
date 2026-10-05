package com.example.minutewatcher.feature.settings.ui

import android.content.res.Resources
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.minutewatcher.core.android.ui.onHorizontalSwipe
import com.example.minutewatcher.core.android.ui.resolveNavigationSwipeDirection
import com.example.minutewatcher.core.android.util.WidgetStringsFactory
import com.example.minutewatcher.core.android.util.WidgetUiMapper
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.WidgetSettingsState
import com.example.minutewatcher.core.model.WidgetUiState
import com.example.minutewatcher.core.resources.R

@Composable
fun WidgetSettingsScreen(
    viewModel: WidgetSettingsViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val strings = remember(context) { WidgetStringsFactory.create(context) }

    val screenUiState =
        remember(uiState.settings, snapshot, strings, context) {
            val settings = uiState.settings ?: return@remember null
            val operatorNames = WidgetStringsFactory.createOperatorNames(context, snapshot.simSnapshots)
            WidgetUiMapper.mapFromSnapshot(
                strings = strings,
                snapshot = snapshot,
                state = settings,
                operatorNames = operatorNames,
            )
        }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .onHorizontalSwipe(
                    resolveNavigationSwipeDirection(
                        navigateForward = false,
                        isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl,
                    ),
                    Constants.UI.SWIPE_NAVIGATION_THRESHOLD_DP.dp,
                ) { onBack() },
    ) {
        if (uiState.isLoadError) {
            ErrorState(
                onRetry = { viewModel.retryLoad() },
                onBack = onBack,
            )
        } else {
            val settings = uiState.settings
            if (settings == null) {
                LoadingState()
            } else {
                WidgetSettingsContent(
                    settings = settings,
                    screenUiState = screenUiState,
                    saveResult = uiState.saveResult,
                    viewModel = viewModel,
                    onBack = onBack,
                )
            }
        }
    }
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
        topBar = { SettingsTopAppBar(onBack = onBack) },
    ) { paddingValues ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            contentAlignment = Alignment.Center,
        ) {
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
    saveResult: SaveResult?,
    viewModel: WidgetSettingsViewModel,
    onBack: () -> Unit,
) {
    var showColorPicker by remember { mutableStateOf(value = false) }
    val snackbarHostState = remember { SnackbarHostState() }

    HandleSaveResultEffect(
        saveResult = saveResult,
        snackbarHostState = snackbarHostState,
        viewModel = viewModel,
    )

    Scaffold(
        topBar = { SettingsTopAppBar(onBack = onBack) },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            BottomPreviewBar(
                uiState = screenUiState,
            )
        },
    ) { paddingValues ->
        WidgetSettingsList(
            modifier = Modifier.padding(paddingValues),
            state = settings,
            uiState = screenUiState,
            viewModel = viewModel,
        ) {
            showColorPicker = true
        }

        if (showColorPicker) {
            ColorPickerWrapper(
                onColorSelected = { color ->
                    viewModel.updateSettings { it.copy(backgroundColor = color) }
                },
                onDismiss = {
                    showColorPicker = false
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTopAppBar(onBack: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(id = R.string.settings_widget_title)) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(id = R.string.navigate_up_content_desc),
                )
            }
        },
    )
}

@Composable
private fun HandleSaveResultEffect(
    saveResult: SaveResult?,
    snackbarHostState: SnackbarHostState,
    viewModel: WidgetSettingsViewModel,
) {
    val resources = LocalResources.current
    val partialSuccessMessage = stringResource(id = R.string.settings_save_partial_success)
    val failedMessage = stringResource(id = R.string.settings_save_failed)
    val partialFailureGeneralMessage = stringResource(id = R.string.settings_save_partial_failure)

    LaunchedEffect(saveResult) {
        if (saveResult != null) {
            try {
                val message =
                    resolveSaveResultMessage(
                        saveResult = saveResult,
                        partialSuccessMessage = partialSuccessMessage,
                        failedMessage = failedMessage,
                        partialFailureGeneralMessage = partialFailureGeneralMessage,
                        resources = resources,
                    )
                message?.let { snackbarHostState.showSnackbar(it) }
            } finally {
                viewModel.consumeSaveResult()
            }
        }
    }
}

private fun resolveSaveResultMessage(
    saveResult: SaveResult,
    partialSuccessMessage: String,
    failedMessage: String,
    partialFailureGeneralMessage: String,
    resources: Resources,
): String? =
    when (saveResult) {
        SaveResult.Success -> null
        is SaveResult.PartialSuccess -> partialSuccessMessage
        is SaveResult.PartialFailure -> {
            if (saveResult.failedSlots.isEmpty()) {
                partialFailureGeneralMessage
            } else {
                val slotsString = saveResult.failedSlots.map { it + 1 }.joinToString(", ")
                resources.getString(R.string.settings_save_partial_failure_slots, slotsString)
            }
        }

        is SaveResult.Failed -> failedMessage
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
