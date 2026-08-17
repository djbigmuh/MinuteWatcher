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
    /**
     * suspend-функция сохранения настроек. Вызывается в CoroutineScope, созданном внутри BottomPreviewBar.
     * Возвращает [SaveResult] для обработки результата.
     */
    onSave: suspend () -> SaveResult,
    onBack: () -> Unit,
) {
    val state by viewModel.settingsState.collectAsStateWithLifecycle()
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val loadError by viewModel.loadError.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    // Строки пересоздаются при смене конфигурации (локаль, ориентация).
    // На AOSP смена локали через AppCompatDelegate.setApplicationLocales()
    // вызывает пересоздание конфигурации. На кастомных прошивках,
    // где это не происходит, строки обновятся при следующем пересоздании
    // Activity — это проблема кастомной прошивки.
    val strings = remember(configuration) { WidgetStringsFactory.create(context) }

    val uiState =
        remember(state, snapshot, strings, context) {
            val nonNullState = state ?: return@remember null
            val operatorNames = WidgetStringsFactory.createOperatorNames(context, snapshot.simSnapshots)
            WidgetUiMapper.mapFromSnapshot(
                strings = strings,
                snapshot = snapshot,
                state = nonNullState,
                operatorNames = operatorNames,
            )
        }

    if (loadError) {
        ErrorState(
            onRetry = { viewModel.retryLoad() },
            onBack = onBack,
        )
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
        isSaving = isSaving,
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
    state: WidgetSettingsState,
    uiState: WidgetUiState?,
    isSaving: Boolean,
    viewModel: WidgetSettingsViewModel,
    onSave: suspend () -> SaveResult,
    onBack: () -> Unit,
) {
    var showColorPicker by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val saveResult by viewModel.saveResult.collectAsStateWithLifecycle()
    val alreadyInProgressMessage = stringResource(id = R.string.settings_save_already_in_progress)

    LaunchedEffect(saveResult) {
        saveResult?.let { result ->
            if (result is SaveResult.AlreadyInProgress) {
                snackbarHostState.showSnackbar(
                    message = alreadyInProgressMessage,
                )
            }
            viewModel.consumeSaveResult()
        }
    }

    // Защита от сохранения дефолтных значений поверх реальных настроек.
    // Если DataStore не ответил (деградация кастомной прошивки),
    // показываем ошибку и предлагаем повторить. На AOSP load()
    // завершается за миллисекунды; таймаут — проблема кастомной прошивки.
    if (state.settingsLoadFailed) {
        // Настройки не загрузились — показываем ошибку вместо контента.
        // Сохранение в этом состоянии запрещено в saveAndNotify().
        ErrorState(
            onRetry = { viewModel.retryLoad() },
            onBack = onBack,
        )
        return
    }

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
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            BottomPreviewBar(
                uiState = uiState,
                isSaving = isSaving,
                onSave = onSave,
            )
        },
    ) { paddingValues ->
        WidgetSettingsList(
            modifier = Modifier.padding(paddingValues),
            state = state,
            uiState = uiState,
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
