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
    /**
     * @param viewModel ViewModel для управления состоянием настроек виджета.
     *   Создаётся через hiltViewModel() в вызывающем коде.
     *   Содержит состояние настроек, флаг ошибки загрузки,
     *   флаг сохранения и результат сохранения.
     */
    viewModel: WidgetSettingsViewModel,
    /**
     * @param onSave suspend-функция сохранения настроек.
     *   Вызывается в BottomPreviewBar через rememberCoroutineScope().
     *   Если пользователь уходит с экрана во время сохранения,
     *   корутина отменяется. Сохранение может не завершиться.
     *   saveMutex освобождается в finally-блоке, что гарантирует
     *   отсутствие deadlock.
     */
    onSave: suspend () -> Unit,
    /**
     * @param onBack функция возврата на предыдущий экран.
     *   Вызывается при нажатии кнопки "Назад" в TopAppBar
     *   или кнопки "Retry" в ErrorState.
     */
    onBack: () -> Unit,
) {
    // collectAsStateWithLifecycle() обеспечивает lifecycle-aware сбор StateFlow.
    // При уходе экрана из lifecycle (STOPPED) сбор приостанавливается,
    // что предотвращает утечки и лишние рекомпозиции.
    // Это стандартная best practice для Compose.
    val state by viewModel.settingsState.collectAsStateWithLifecycle()
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val loadError by viewModel.loadError.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    // Строки пересоздаются при изменении конфигурации (локаль, ориентация и т. д.).
    // Строгое соблюдение API: на AOSP смена локали через AppCompatDelegate.setApplicationLocales()
    // вызывает пересоздание конфигурации. На кастомных прошивках, где это не происходит,
    // строки обновятся при следующем пересоздании Activity — это проблема кастомной прошивки.
    val strings = remember(configuration) { WidgetStringsFactory.create(context) }

    // uiState вычисляется через remember(state, snapshot, strings, context).
    // Перевычисляется при изменении любого из ключей.
    // Это предотвращает лишние вычисления при рекомпозиции.
    //
    // strings пересоздаются при изменении конфигурации (локаль).
    // snapshot обновляется при изменении данных.
    // state обновляется при изменении настроек.
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

    // loadError проверяется перед показом WidgetSettingsContent.
    // При loadError = true показывается ErrorState с кнопкой Retry.
    // Это предотвращает показ некорректных настроек.
    //
    // Строгое соблюдение API: на AOSP загрузка настроек завершается за
    // миллисекунды. Ошибка загрузки указывает на деградацию DataStore
    // на кастомной прошивке. Это проблема кастомной прошивки.
    if (loadError) {
        ErrorState(
            onRetry = { viewModel.retryLoad() },
            onBack = onBack,
        )
        return
    }

    // nonNullState проверяется на null перед показом WidgetSettingsContent.
    // Если state = null, показывается LoadingState.
    // Это предотвращает NullPointerException в WidgetSettingsContent.
    //
    // state = null НЕ должен возникать после успешной загрузки.
    // Если это происходит, это указывает на баг в логике загрузки.
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
            // SimCardTopBar — TopAppBar для экрана настроек виджета.
            // Содержит заголовок "Настройки виджета" и кнопку "Назад".
            // Кнопка "Назад" вызывает onBack для возврата на предыдущий экран.
            //
            // TopAppBar использует ExperimentalMaterial3Api.
            // Это стандартная best practice для Material 3.
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
    onSave: suspend () -> Unit,
    onBack: () -> Unit,
) {
    // showColorPicker — локальное состояние для управления ColorPickerDialog.
    // При showColorPicker = true показывается ColorPickerWrapper.
    // При выборе цвета или закрытии диалога showColorPicker устанавливается в false.
    //
    // При изменении конфигурации (поворот экрана) состояние теряется
    // и диалог исчезает. Пользователь может открыть его повторно.
    // Это допустимое поведение для модального диалога.
    var showColorPicker by remember { mutableStateOf(false) }
    // snackbarHostState — состояние для показа Snackbar.
    // Используется в LaunchedEffect(saveResult) для показа результата сохранения.
    //
    // При изменении конфигурации состояние теряется и Snackbar исчезает.
    // Это допустимое поведение: пользователь может повторить действие
    // для повторного показа.
    val snackbarHostState = remember { SnackbarHostState() }
    // saveResult собирается через collectAsStateWithLifecycle().
    // При уходе экрана из lifecycle (STOPPED) сбор приостанавливается.
    // Это предотвращает утечки и лишние рекомпозиции.
    //
    // LaunchedEffect keyed on saveResult обрабатывает результат.
    // После consumeSaveResult() saveResult становится null,
    // эффект перезапускается с null и ничего не делает.
    val saveResult by viewModel.saveResult.collectAsStateWithLifecycle()

    val successMessage = stringResource(id = R.string.settings_saved_toast)
    val partialSuccessMessage = stringResource(id = R.string.settings_save_partial_success)
    val failedMessage = stringResource(id = R.string.settings_save_failed)
    val alreadyInProgressMessage = stringResource(id = R.string.settings_save_already_in_progress)

    // LaunchedEffect keyed on saveResult: перезапускается при изменении результата.
    // После consumeSaveResult() saveResult становится null, эффект перезапускается
    // с null и ничего не делает. Это предотвращает повторный показ Snackbar
    // при рекомпозиции.
    LaunchedEffect(saveResult) {
        saveResult?.let { result ->
            val message =
                when (result) {
                    SaveResult.Success -> successMessage
                    is SaveResult.PartialSuccess -> partialSuccessMessage
                    is SaveResult.PartialFailure -> result.message
                    SaveResult.Failed -> failedMessage
                    SaveResult.AlreadyInProgress -> alreadyInProgressMessage
                }

            snackbarHostState.showSnackbar(message)

            if (result is SaveResult.Success ||
                result is SaveResult.PartialSuccess ||
                result is SaveResult.PartialFailure
            ) {
                // В данной реализации мы остаемся на экране, чтобы пользователь мог
                // увидеть Snackbar с подтверждением.
            }

            viewModel.consumeSaveResult()
        }
    }

    // Scaffold обеспечивает структуру экрана: TopAppBar, content, bottomBar.
    // TopAppBar содержит заголовок и кнопку "Назад".
    // content содержит WidgetSettingsList (при успешной загрузке)
    // или ErrorState (при ошибке загрузки).
    // bottomBar содержит BottomPreviewBar.
    //
    // snackbarHost привязан к snackbarHostState для показа Snackbar.
    Scaffold(
        topBar = {
            // SimCardTopBar — TopAppBar для экрана настроек виджета.
            // Содержит заголовок "Настройки виджета" и кнопку "Назад".
            // Кнопка "Назад" вызывает onBack для возврата на предыдущий экран.
            //
            // TopAppBar использует ExperimentalMaterial3Api.
            // Это стандартная best practice для Material 3.
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
        // BottomPreviewBar показывается внизу экрана.
        // Содержит превью виджета и кнопку "Сохранить".
        // Кнопка "Сохранить" вызывает onSave в rememberCoroutineScope().
        //
        // При изменении конфигурации BottomPreviewBar пересоздаётся.
        // Состояние showColorPicker теряется. Это допустимое поведение.
        bottomBar = {
            BottomPreviewBar(
                uiState = uiState,
                isSaving = isSaving,
                onSave = onSave,
            )
        },
    ) { paddingValues ->
        // WidgetSettingsList содержит список настроек виджета.
        // Включает GeneralSettingsSection, NotificationSettingsSection
        // и SimCardsSettingsSection.
        //
        // Список прокручиваемый (verticalScroll).
        // При изменении конфигурации список пересоздаётся.
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
    // WidgetSettingsList содержит список настроек виджета.
    // Включает GeneralSettingsSection, NotificationSettingsSection
    // и SimCardsSettingsSection.
    //
    // Список прокручиваемый (verticalScroll).
    // При изменении конфигурации список пересоздаётся.
    val scrollState = rememberScrollState()
    // Column обеспечивает вертикальное расположение секций настроек.
    // verticalScroll обеспечивает прокрутку при переполнении.
    // padding(16.dp) обеспечивает отступы от краёв экрана.
    //
    // Text используется для заголовков и меток настроек.
    // style = MaterialTheme.typography.titleSmall для заголовков секций.
    // style = MaterialTheme.typography.bodyMedium для меток.
    // color = enabledContentColor(enabled) обеспечивает корректный цвет
    // в зависимости от состояния enabled.
    Column(
        modifier =
            modifier
                .verticalScroll(scrollState)
                .padding(16.dp),
    ) {
        // GeneralSettingsSection содержит общие настройки виджета:
        // - Режим компоновки (layout mode)
        // - Расположение SIM (sim arrangement)
        // - Прозрачность фона (transparency)
        // - Размер шрифта (font size)
        // - Цвет фона (background color)
        //
        // Все настройки сохраняются через onUpdateLayoutMode, onUpdateSimArrangement,
        // onUpdateTransparency, onUpdateFontSize, onShowColorPicker.
        GeneralSettingsSection(
            state = state,
            enabled = true,
            onUpdateLayoutMode = { mode -> viewModel.updateSettings { it.copy(layoutMode = mode) } },
            onUpdateSimArrangement = { arr -> viewModel.updateSettings { it.copy(simArrangement = arr) } },
            onUpdateTransparency = { tr -> viewModel.updateSettings { it.copy(transparency = tr) } },
            onUpdateFontSize = { fs -> viewModel.updateSettings { it.copy(fontSize = fs) } },
            onShowColorPicker = onShowColorPicker,
        )

        // Spacer обеспечивает визуальное разделение между секциями настроек.
        // Высота 16.dp обеспечивает достаточное пространство между секциями.
        Spacer(modifier = Modifier.height(16.dp))

        // NotificationSettingsSection содержит настройки уведомлений:
        // - Уведомление о низком остатке минут
        // - Уведомление о низком остатке трафика
        //
        // Настройки сохраняются через onUpdateNotifyMinutes, onUpdateNotifyTraffic.
        NotificationSettingsSection(
            state = state,
            enabled = true,
            onUpdateNotifyMinutes = { en -> viewModel.updateSettings { it.copy(notifyLowMinutes = en) } },
            onUpdateNotifyTraffic = { en -> viewModel.updateSettings { it.copy(notifyLowTraffic = en) } },
        )

        // Spacer обеспечивает визуальное разделение между секциями настроек.
        // Высота 16.dp обеспечивает достаточное пространство между секциями.
        Spacer(modifier = Modifier.height(16.dp))

        // SimCardsSettingsSection содержит настройки SIM-карт:
        // - Показ SIM-карты
        // - Показ иконки
        // - Показ имени
        // - Показ минут
        // - Показ лимита минут
        // - Показ трафика
        // - Показ лимита трафика
        //
        // Настройки сохраняются через onUpdateSimSettings.
        // При simListLoadFailed = true секция показывает сообщение об ошибке.
        //
        // Строгое соблюдение API: на AOSP список SIM доступен всегда.
        // simListLoadFailed = true возможен только при деградации DataStore
        // на кастомной прошивке. Это проблема кастомной прошивки.
        SimCardsSettingsSection(
            state = state,
            uiState = uiState,
            enabled = !state.simListLoadFailed,
            onUpdateSimSettings = viewModel::updateSimSettings,
        )
    }
}
