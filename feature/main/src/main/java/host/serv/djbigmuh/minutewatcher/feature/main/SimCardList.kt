package host.serv.djbigmuh.minutewatcher.feature.main

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import host.serv.djbigmuh.minutewatcher.core.model.SimCardUiModel
import host.serv.djbigmuh.minutewatcher.feature.main.ui.components.ResetConfirmationDialog
import host.serv.djbigmuh.minutewatcher.feature.main.ui.components.SimCardItem

/**
 * Renders SIM card list.
 * Each SIM card is rendered via SimCardListRow.
 */
@Composable
fun SimCardList(
    simCards: List<SimCardUiModel>,
    listState: LazyListState,
    highlightedSlot: Int? = null,
    onSetLimitDialog: (LimitDialogData) -> Unit,
    onResetUsage: (slot: Int) -> Unit,
    onToggleAutoReset: (slot: Int, enabled: Boolean) -> Unit,
    onSetDayDialog: (DayDialogData) -> Unit,
    onSetAdjustDialog: (AdjustDialogData) -> Unit,
    onShowTrafficInfo: () -> Unit,
) {
    var showResetConfirmSlot by remember { mutableStateOf<Int?>(null) }

    LazyColumn(
        state = listState,
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 80.dp),
    ) {
        items(
            items = simCards,
            key = { it.slot },
        ) { model ->
            SimCardListRow(
                model = model,
                highlighted = highlightedSlot != null && highlightedSlot == model.slot,
                onReset = { showResetConfirmSlot = model.slot },
                onSetLimitDialog = onSetLimitDialog,
                onToggleAutoReset = onToggleAutoReset,
                onSetDayDialog = onSetDayDialog,
                onSetAdjustDialog = onSetAdjustDialog,
                onShowTrafficInfo = onShowTrafficInfo,
            )
        }
    }

    showResetConfirmSlot?.let { slot ->
        key(slot) {
            ResetConfirmationDialog(
                onDismiss = { showResetConfirmSlot = null },
                onConfirm = {
                    onResetUsage(slot)
                    showResetConfirmSlot = null
                },
            )
        }
    }
}

/**
 * Renders a single SIM card item in the list.
 */
@Composable
private fun SimCardListRow(
    model: SimCardUiModel,
    highlighted: Boolean,
    onReset: () -> Unit,
    onSetLimitDialog: (LimitDialogData) -> Unit,
    onToggleAutoReset: (slot: Int, enabled: Boolean) -> Unit,
    onSetDayDialog: (DayDialogData) -> Unit,
    onSetAdjustDialog: (AdjustDialogData) -> Unit,
    onShowTrafficInfo: () -> Unit,
) {
    SimCardItem(
        model = model,
        highlighted = highlighted,
        onSetLimits = {
            onSetLimitDialog(
                LimitDialogData(
                    model.slot,
                    model.operatorName,
                    model.minutesLimit,
                    model.trafficLimitMb,
                    model.rolloverEnabled,
                ),
            )
        },
        onResetCounters = onReset,
        onToggleAutoReset = { enabled -> onToggleAutoReset(model.slot, enabled) },
        onEditAutoResetDay = {
            onSetDayDialog(DayDialogData(model.slot, model.autoResetDay))
        },
        onAdjustMinutes = {
            onSetAdjustDialog(
                AdjustDialogData(model.slot, model.minutesRemaining.toFloat(), true),
            )
        },
        onAdjustTraffic = {
            onSetAdjustDialog(
                AdjustDialogData(model.slot, model.trafficRemainingGb ?: 0f, false),
            )
        },
        onShowTrafficInfo = onShowTrafficInfo,
    )
}
