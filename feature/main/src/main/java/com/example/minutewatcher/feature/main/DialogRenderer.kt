package com.example.minutewatcher.feature.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.minutewatcher.core.resources.R
import com.example.minutewatcher.feature.main.ui.components.AboutDialog
import com.example.minutewatcher.feature.main.ui.components.AdjustmentComposeDialog
import com.example.minutewatcher.feature.main.ui.components.DayPickerComposeDialog
import com.example.minutewatcher.feature.main.ui.components.OnboardingDialog
import com.example.minutewatcher.feature.main.ui.components.SimLimitComposeDialog
import com.example.minutewatcher.feature.main.ui.components.SimpleMessageDialog
import com.example.minutewatcher.feature.main.ui.components.UsageAccessDialog

/**
 * Renders dialogs for the SIM card screen.
 * Acts as the central component for managing screen modal windows.
 * Displays dialogs depending on [MainViewModel] state, providing
 * a unified entry point for rendering all user interactions
 * via dialogs.
 *
 * Handled dialogs:
 * - [SimLimitComposeDialog]: Setting minutes and traffic limits.
 * - [DayPickerComposeDialog]: Selecting the day for automatic statistics reset.
 * - [AdjustmentComposeDialog]: Manual adjustment of current remaining usage.
 * - [SimpleMessageDialog]: Informational messages (e.g., about traffic limitations).
 * - [UsageAccessDialog]: Requesting system access to usage statistics.
 * - [OnboardingDialog]: Onboarding screen for new users.
 * - [AboutDialog]: About application dialog.
 *
 * @param viewModel ViewModel for managing dialog state and executing confirmation business logic.
 * @param showUsageAccessDialog Flag for showing usage access dialog (controlled by external permission logic).
 * @param showOnboardingDialog Flag for showing onboarding dialog.
 * @param showAboutDialog Flag for showing about dialog.
 * @param onOpenUsageAccessSettings Callback to open system access settings.
 * @param onEmailAuthor Callback to compose email to author.
 */
@Composable
fun DialogRenderer(
    viewModel: MainViewModel,
    showUsageAccessDialog: Boolean,
    showOnboardingDialog: Boolean,
    showAboutDialog: Boolean,
    onOpenUsageAccessSettings: () -> Unit,
    onEmailAuthor: () -> Unit,
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

    if (showAboutDialog) {
        AboutDialog(
            versionName = viewModel.versionName,
            versionCode = viewModel.code,
            onDismiss = { viewModel.setShowAboutDialog(false) },
            onEmailAuthor = {
                viewModel.setShowAboutDialog(false)
                onEmailAuthor()
            },
        )
    }
}

/**
 * Renders limit setting dialog for a SIM card.
 * Displayed when showLimitDialog != null.
 *
 * @param data Dialog data (slot, operator name, current limits).
 * @param viewModel ViewModel for state management.
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
 * Renders automatic reset day selection dialog.
 * Displayed when showDayDialog != null.
 *
 * @param data Dialog data (slot, current day).
 * @param viewModel ViewModel for state management.
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
 * Renders adjustment dialog for minutes or traffic remaining.
 * Displayed when showAdjustDialog != null.
 *
 * @param data Dialog data (slot, current value, type: minutes/traffic).
 * @param viewModel ViewModel for state management.
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
