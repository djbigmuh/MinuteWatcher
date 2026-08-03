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

@Composable
fun SimCardList(
    simCards: List<SimCardUiModel>,
    listState: LazyListState,
    viewModel: MainViewModel,
) {
    var showResetConfirmSlot by remember { mutableStateOf<Int?>(null) }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 80.dp),
    ) {
        items(simCards) { model ->
            SimCardListRow(
                model = model,
                viewModel = viewModel,
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

@Composable
private fun SimCardListRow(
    model: SimCardUiModel,
    viewModel: MainViewModel,
    onReset: () -> Unit,
) {
    SimCardItem(
        model = model,
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
