package com.example.minutewatcher.feature.main

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
import com.example.minutewatcher.core.model.SimCardUiModel
import com.example.minutewatcher.feature.main.ui.components.ResetConfirmationDialog
import com.example.minutewatcher.feature.main.ui.components.SimCardItem

/**
 * Renders SIM card list.
 * Each SIM card is rendered via SimCardListRow.
 *
 * @param simCards List of SIM cards.
 * @param listState Scroll list state.
 * @param viewModel ViewModel for state management.
 */
@Composable
fun SimCardList(
    simCards: List<SimCardUiModel>,
    listState: LazyListState,
    viewModel: MainViewModel,
    highlightedSlot: Int? = null,
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
                viewModel = viewModel,
                highlighted = highlightedSlot != null && highlightedSlot == model.slot,
                onReset = { showResetConfirmSlot = model.slot },
            )
        }
    }

    showResetConfirmSlot?.let { slot ->
        key(slot) {
            ResetConfirmationDialog(
                onDismiss = { showResetConfirmSlot = null },
                onConfirm = {
                    viewModel.resetUsage(slot)
                    showResetConfirmSlot = null
                },
            )
        }
    }
}

/**
 * Renders a single SIM card item in the list.
 * Contains icon, operator name, minutes and traffic state,
 * limit setting and reset buttons.
 *
 * @param model SIM card model.
 * @param viewModel ViewModel for state management.
 * @param onReset Callback for SIM card reset.
 */
@Composable
private fun SimCardListRow(
    model: SimCardUiModel,
    viewModel: MainViewModel,
    highlighted: Boolean,
    onReset: () -> Unit,
) {
    SimCardItem(
        model = model,
        highlighted = highlighted,
        onSetLimits = {
            viewModel.setLimitDialog(
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
        onToggleAutoReset = { enabled -> viewModel.toggleAutoReset(model.slot, enabled) },
        onEditAutoResetDay = {
            viewModel.setDayDialog(DayDialogData(model.slot, model.autoResetDay))
        },
        onAdjustMinutes = {
            viewModel.setAdjustDialog(
                AdjustDialogData(model.slot, model.minutesRemaining.toFloat(), true),
            )
        },
        onAdjustTraffic = {
            viewModel.setAdjustDialog(
                AdjustDialogData(model.slot, model.trafficRemainingGb, false),
            )
        },
        onShowTrafficInfo = { viewModel.setTrafficLimitationDialog(true) },
    )
}
