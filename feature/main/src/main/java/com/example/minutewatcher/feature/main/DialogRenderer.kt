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

    showLimitDialog?.let { data ->
        key(data) {
            SimLimitComposeDialog(
                operatorName = data.name,
                initialMinutes = data.minutes,
                initialTrafficMb = data.traffic,
                initialRolloverEnabled = data.rolloverEnabled,
                onDismiss = { viewModel.setLimitDialog(null) },
                onConfirm = { min, traf, rollover ->
                    viewModel.updateLimits(data.slot, min, traf, rollover)
                    viewModel.setLimitDialog(null)
                },
                onReset = {
                    viewModel.updateLimits(data.slot, 0, 0, false)
                    viewModel.setLimitDialog(null)
                },
            )
        }
    }

    showDayDialog?.let { data ->
        key(data) {
            DayPickerComposeDialog(
                initialDay = data.day,
                onDismiss = { viewModel.setDayDialog(null) },
                onDaySelected = { day ->
                    viewModel.updateAutoResetDay(data.slot, day)
                    viewModel.setDayDialog(null)
                },
            )
        }
    }

    showAdjustDialog?.let { data ->
        key(data) {
            AdjustmentComposeDialog(
                currentValue = data.currentValue,
                isMinutes = data.isMinutes,
                onDismiss = { viewModel.setAdjustDialog(null) },
                onConfirm = { value ->
                    if (data.isMinutes) {
                        viewModel.adjustRemainingMinutes(data.slot, value.toInt())
                    } else {
                        viewModel.adjustRemainingTraffic(data.slot, value)
                    }
                    viewModel.setAdjustDialog(null)
                },
            )
        }
    }

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
