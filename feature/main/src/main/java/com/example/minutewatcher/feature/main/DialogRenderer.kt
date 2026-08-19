package com.example.minutewatcher.feature.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.minutewatcher.core.resources.R
import com.example.minutewatcher.feature.main.ui.components.AdjustmentComposeDialog
import com.example.minutewatcher.feature.main.ui.components.DayPickerComposeDialog
import com.example.minutewatcher.feature.main.ui.components.OnboardingDialog
import com.example.minutewatcher.feature.main.ui.components.SimLimitComposeDialog
import com.example.minutewatcher.feature.main.ui.components.SimpleMessageDialog
import com.example.minutewatcher.feature.main.ui.components.UsageAccessDialog

/**
 * Рендерит диалоги для экрана SIM-карт.
 * Является центральным компонентом управления модальными окнами экрана.
 * Показывает диалоги в зависимости от состояния [MainViewModel], обеспечивая
 * единую точку входа для рендеринга всех пользовательских взаимодействий
 * через диалоги.
 *
 * Обрабатываемые диалоги:
 * - [SimLimitComposeDialog]: Установка лимитов минут и трафика.
 * - [DayPickerComposeDialog]: Выбор дня автоматического сброса статистики.
 * - [AdjustmentComposeDialog]: Ручная корректировка текущего остатка.
 * - [SimpleMessageDialog]: Информационные сообщения (например, об ограничениях трафика).
 * - [UsageAccessDialog]: Запрос системного доступа к статистике использования.
 * - [OnboardingDialog]: Ознакомительный экран для новых пользователей.
 *
 * @param viewModel ViewModel для управления состоянием диалогов и выполнения бизнес-логики подтверждения.
 * @param showUsageAccessDialog Флаг показа диалога доступа к использованию (контролируется внешней логикой разрешений).
 * @param showOnboardingDialog Флаг показа диалога онбординга.
 * @param onOpenUsageAccessSettings Колбэк для открытия системных настроек доступа.
 */
@Composable
fun DialogRenderer(
    viewModel: MainViewModel,
    showUsageAccessDialog: Boolean,
    showOnboardingDialog: Boolean,
    onOpenUsageAccessSettings: () -> Unit,
) {
    val showLimitDialog by viewModel.showLimitDialog.collectAsStateWithLifecycle()
    val showDayDialog by viewModel.showDayDialog.collectAsStateWithLifecycle()
    val showAdjustDialog by viewModel.showAdjustDialog.collectAsStateWithLifecycle()
    val showTrafficLimitationDialog by viewModel.showTrafficLimitationDialog.collectAsStateWithLifecycle()

    LimitDialog(showLimitDialog, viewModel)
    DayDialog(showDayDialog, viewModel)
    AdjustDialog(showAdjustDialog, viewModel)

    if (showTrafficLimitationDialog) {
        SimpleMessageDialog(
            title = stringResource(id = R.string.traffic_tracking_limitation_title),
            text = stringResource(id = R.string.traffic_tracking_limitation_body),
            onDismiss = { viewModel.setTrafficLimitationDialog(false) },
        )
    }

    if (showUsageAccessDialog) {
        UsageAccessDialog(
            onDismiss = { viewModel.setUsageAccessDialog(false) },
            onSettings = {
                viewModel.setUsageAccessDialog(false)
                onOpenUsageAccessSettings()
            },
        )
    }

    if (showOnboardingDialog) {
        OnboardingDialog(onDismiss = { viewModel.completeOnboarding() })
    }
}

/**
 * Рендерит диалог установки лимитов для SIM-карты.
 * Показывается при showLimitDialog != null.
 *
 * @param data Данные диалога (slot, имя оператора, текущие лимиты).
 * @param viewModel ViewModel для управления состоянием.
 */
@Composable
private fun LimitDialog(
    data: LimitDialogData?,
    viewModel: MainViewModel,
) {
    data?.let {
        key(it) {
            SimLimitComposeDialog(
                slot = it.slot,
                operatorName = it.name,
                initialMinutes = it.minutes,
                initialTrafficMb = it.traffic,
                initialRolloverEnabled = it.rolloverEnabled,
                onDismiss = { viewModel.setLimitDialog(null) },
                onConfirm = { min, traf, rollover ->
                    viewModel.updateLimits(it.slot, min, traf, rollover)
                    viewModel.setLimitDialog(null)
                },
                onReset = {
                    viewModel.updateLimits(it.slot, 0, 0, false)
                    viewModel.setLimitDialog(null)
                },
            )
        }
    }
}

/**
 * Рендерит диалог выбора дня автоматического сброса.
 * Показывается при showDayDialog != null.
 *
 * @param data Данные диалога (slot, текущий день).
 * @param viewModel ViewModel для управления состоянием.
 */
@Composable
private fun DayDialog(
    data: DayDialogData?,
    viewModel: MainViewModel,
) {
    data?.let {
        key(it) {
            DayPickerComposeDialog(
                initialDay = it.day,
                onDismiss = { viewModel.setDayDialog(null) },
                onDaySelected = { day ->
                    viewModel.updateAutoResetDay(it.slot, day)
                    viewModel.setDayDialog(null)
                },
            )
        }
    }
}

/**
 * Рендерит диалог корректировки остатка минут или трафика.
 * Показывается при showAdjustDialog != null.
 *
 * @param data Данные диалога (slot, текущее значение, тип: минуты/трафик).
 * @param viewModel ViewModel для управления состоянием.
 */
@Composable
private fun AdjustDialog(
    data: AdjustDialogData?,
    viewModel: MainViewModel,
) {
    data?.let {
        key(it) {
            AdjustmentComposeDialog(
                currentValue = it.currentValue,
                isMinutes = it.isMinutes,
                onDismiss = { viewModel.setAdjustDialog(null) },
                onConfirm = { value ->
                    if (it.isMinutes) {
                        viewModel.adjustRemainingMinutes(it.slot, value.toInt())
                    } else {
                        viewModel.adjustRemainingTraffic(it.slot, value)
                    }
                    viewModel.setAdjustDialog(null)
                },
            )
        }
    }
}
