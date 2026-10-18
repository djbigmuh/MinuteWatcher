package host.serv.djbigmuh.minutewatcher.feature.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.res.stringResource
import host.serv.djbigmuh.minutewatcher.core.resources.R
import host.serv.djbigmuh.minutewatcher.feature.main.ui.components.AboutDialog
import host.serv.djbigmuh.minutewatcher.feature.main.ui.components.AdjustmentComposeDialog
import host.serv.djbigmuh.minutewatcher.feature.main.ui.components.DayPickerComposeDialog
import host.serv.djbigmuh.minutewatcher.feature.main.ui.components.OnboardingDialog
import host.serv.djbigmuh.minutewatcher.feature.main.ui.components.SimLimitComposeDialog
import host.serv.djbigmuh.minutewatcher.feature.main.ui.components.SimpleMessageDialog
import host.serv.djbigmuh.minutewatcher.feature.main.ui.components.UsageAccessDialog

/**
 * Renders dialogs for the SIM card screen.
 * Acts as the central component for managing screen modal windows.
 * Displays dialogs depending on state, providing
 * a unified entry point for rendering all user interactions
 * via dialogs.
 */
@Composable
fun DialogRenderer(
    showLimitDialog: LimitDialogData?,
    showDayDialog: DayDialogData?,
    showAdjustDialog: AdjustDialogData?,
    showTrafficLimitationDialog: Boolean,
    showUsageAccessDialog: Boolean,
    showOnboardingDialog: Boolean,
    showAboutDialog: Boolean,
    versionName: String,
    versionCode: Int,
    onDismissLimitDialog: () -> Unit,
    onConfirmLimits: (slot: Int, min: Int, traf: Int, rollover: Boolean) -> Unit,
    onResetLimits: (slot: Int) -> Unit,
    onDismissDayDialog: () -> Unit,
    onConfirmAutoResetDay: (slot: Int, day: Int) -> Unit,
    onDismissAdjustDialog: () -> Unit,
    onConfirmAdjustMinutes: (slot: Int, value: Int) -> Unit,
    onConfirmAdjustTraffic: (slot: Int, value: Float) -> Unit,
    onDismissTrafficLimitationDialog: () -> Unit,
    onDismissUsageAccessDialog: () -> Unit,
    onOpenUsageAccessSettings: () -> Unit,
    onCompleteOnboarding: () -> Unit,
    onDismissAboutDialog: () -> Unit,
    onEmailAuthor: () -> Unit,
) {
    LimitDialog(
        data = showLimitDialog,
        onDismiss = onDismissLimitDialog,
        onConfirm = onConfirmLimits,
        onReset = onResetLimits,
    )
    DayDialog(
        data = showDayDialog,
        onDismiss = onDismissDayDialog,
        onDaySelected = onConfirmAutoResetDay,
    )
    AdjustDialog(
        data = showAdjustDialog,
        onDismiss = onDismissAdjustDialog,
        onConfirmMinutes = onConfirmAdjustMinutes,
        onConfirmTraffic = onConfirmAdjustTraffic,
    )

    if (showTrafficLimitationDialog) {
        SimpleMessageDialog(
            title = stringResource(id = R.string.traffic_tracking_limitation_title),
            text = stringResource(id = R.string.traffic_tracking_limitation_body),
            onDismiss = onDismissTrafficLimitationDialog,
        )
    }

    if (showUsageAccessDialog) {
        UsageAccessDialog(
            onDismiss = onDismissUsageAccessDialog,
            onSettings = {
                onDismissUsageAccessDialog()
                onOpenUsageAccessSettings()
            },
        )
    }

    if (showOnboardingDialog) {
        OnboardingDialog(onDismiss = onCompleteOnboarding)
    }

    if (showAboutDialog) {
        AboutDialog(
            versionName = versionName,
            versionCode = versionCode,
            onDismiss = onDismissAboutDialog,
            onEmailAuthor = {
                onDismissAboutDialog()
                onEmailAuthor()
            },
        )
    }
}

/**
 * Renders limit setting dialog for a SIM card.
 * Displayed when data != null.
 */
@Composable
private fun LimitDialog(
    data: LimitDialogData?,
    onDismiss: () -> Unit,
    onConfirm: (slot: Int, min: Int, traf: Int, rollover: Boolean) -> Unit,
    onReset: (slot: Int) -> Unit,
) {
    data?.let {
        key(it) {
            SimLimitComposeDialog(
                slot = it.slot,
                operatorName = it.name,
                initialMinutes = it.minutes,
                initialTrafficMb = it.traffic,
                initialRolloverEnabled = it.rolloverEnabled,
                onDismiss = onDismiss,
                onConfirm = { min, traf, rollover -> onConfirm(it.slot, min, traf, rollover) },
                onReset = { onReset(it.slot) },
            )
        }
    }
}

/**
 * Renders automatic reset day selection dialog.
 * Displayed when data != null.
 */
@Composable
private fun DayDialog(
    data: DayDialogData?,
    onDismiss: () -> Unit,
    onDaySelected: (slot: Int, day: Int) -> Unit,
) {
    data?.let {
        key(it) {
            DayPickerComposeDialog(
                initialDay = it.day,
                onDismiss = onDismiss,
                onDaySelected = { day -> onDaySelected(it.slot, day) },
            )
        }
    }
}

/**
 * Renders adjustment dialog for minutes or traffic remaining.
 * Displayed when data != null.
 */
@Composable
private fun AdjustDialog(
    data: AdjustDialogData?,
    onDismiss: () -> Unit,
    onConfirmMinutes: (slot: Int, value: Int) -> Unit,
    onConfirmTraffic: (slot: Int, value: Float) -> Unit,
) {
    data?.let {
        key(it) {
            AdjustmentComposeDialog(
                currentValue = it.currentValue,
                isMinutes = it.isMinutes,
                onDismiss = onDismiss,
                onConfirm = { value ->
                    if (it.isMinutes) {
                        onConfirmMinutes(it.slot, value.toInt())
                    } else {
                        onConfirmTraffic(it.slot, value)
                    }
                },
            )
        }
    }
}
