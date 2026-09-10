package com.example.minutewatcher.feature.main

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Основной экран SIM-карт.
 * Является центральным экраном приложения, отображающим текущее состояние
 * потребления минут и мобильного трафика для всех активных SIM-карт.
 *
 * Структура экрана:
 * - TopAppBar: Управление темой, языком и переход к настройкам виджета.
 * - Content: Список SIM-карт с детальной статистикой и баннерами статуса.
 * - FloatingActionButton: Быстрый доступ к инструкции по добавлению виджета.
 * - SnackbarHost: Отображение ошибок выполнения операций.
 *
 * @param viewModel ViewModel для управления состоянием экрана. Обеспечивает
 *   реактивное обновление данных и управление состоянием диалогов.
 * @param actions Контейнер стабильных колбэков для обработки действий пользователя.
 */
@Composable
fun SimCardScreen(
    viewModel: MainViewModel,
    actions: SimCardScreenActions,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val trackingBannerState by viewModel.trackingBannerState.collectAsStateWithLifecycle()
    val highlightedSlot by viewModel.highlightedSlot.collectAsStateWithLifecycle()
    val appTheme by viewModel.appTheme.collectAsStateWithLifecycle()
    val appLanguage by viewModel.appLanguage.collectAsStateWithLifecycle()
    val showOnboardingDialog by viewModel.showOnboardingDialog.collectAsStateWithLifecycle()
    val showUsageAccessDialog by viewModel.showUsageAccessDialog.collectAsStateWithLifecycle()

    val lastOperationError by viewModel.lastOperationError.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    lastOperationError?.let { error ->
        @Suppress("SpreadOperator")
        val message = stringResource(error.messageRes, *error.args.toTypedArray())
        LaunchedEffect(error) {
            snackbarHostState.showSnackbar(
                message = message,
                withDismissAction = true,
            )
            viewModel.dismissOperationError()
        }
    }

    Scaffold(
        modifier =
            Modifier
                .fillMaxSize()
                .settingsDragInput { actions.onOpenSettings() },
        topBar = {
            SimCardTopBar(
                currentTheme = appTheme,
                currentLanguage = appLanguage,
                onThemeChange = actions.onThemeChange,
                onLanguageChange = actions.onLanguageChange,
                onOpenSettings = actions.onOpenSettings,
            )
        },
        floatingActionButton = { MainFab(onAddWidget = actions.onAddWidget) },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { paddingValues ->
        MainScreenContent(
            uiState = uiState,
            paddingValues = paddingValues,
            viewModel = viewModel,
            trackingBannerState = trackingBannerState,
            highlightedSlot = highlightedSlot,
            actions = actions,
        )

        DialogRenderer(
            viewModel = viewModel,
            showUsageAccessDialog = showUsageAccessDialog,
            showOnboardingDialog = showOnboardingDialog,
            onOpenUsageAccessSettings = actions.onOpenUsageAccessSettings,
        )
    }
}

private fun Modifier.settingsDragInput(onOpenSettings: () -> Unit): Modifier =
    this.pointerInput(Unit) {
        var totalDrag = 0f
        detectHorizontalDragGestures(
            onDragStart = { totalDrag = 0f },
            onDragEnd = {
                if (totalDrag < -200f) {
                    onOpenSettings()
                }
            },
            onDragCancel = { totalDrag = 0f },
            onHorizontalDrag = { _, dragAmount ->
                totalDrag += dragAmount
            },
        )
    }
