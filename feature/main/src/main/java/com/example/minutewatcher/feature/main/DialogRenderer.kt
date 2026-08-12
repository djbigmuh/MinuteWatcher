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
