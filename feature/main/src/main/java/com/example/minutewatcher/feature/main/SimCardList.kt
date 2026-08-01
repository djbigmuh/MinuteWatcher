package com.example.minutewatcher.feature.main

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.minutewatcher.core.model.SimCardUiModel
import com.example.minutewatcher.feature.main.ui.components.ResetConfirmationDialog
import com.example.minutewatcher.feature.main.ui.components.SimCardItem
import com.example.minutewatcher.feature.main.ui.components.TrackingBanners

@Composable
fun SimCardList(
    simCards: List<SimCardUiModel>,
    listState: LazyListState,
    viewModel: MainViewModel,
    trackingBannerState: TrackingBannerState,
    onGrantPhonePermission: () -> Unit,
    onShowUsageAccessDialog: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onGrantNotificationPermission: () -> Unit,
) {
    var showResetConfirmSlot by remember { mutableStateOf<Int?>(null) }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 80.dp),
    ) {
        item {
            TrackingBanners(
                state = trackingBannerState,
                onGrantPhonePermission = onGrantPhonePermission,
                onShowUsageAccessDialog = onShowUsageAccessDialog,
                onOpenAppSettings = onOpenAppSettings,
                onRetryAppSearch = { viewModel.retryAppSearch() },
                onRetryCalls = { viewModel.retryCalls() },
                onRetryShortcuts = { viewModel.retryShortcuts() },
                onRetryTraffic = { viewModel.retryTraffic() },
                onGrantNotificationPermission = onGrantNotificationPermission,
            )
        }
        items(simCards) { model ->
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
                onResetCounters = { showResetConfirmSlot = model.slot },
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
    }

    showResetConfirmSlot?.let { slot ->
        ResetConfirmationDialog(onDismiss = { showResetConfirmSlot = null }, onConfirm = {
            viewModel.resetUsage(slot)
            showResetConfirmSlot = null
        })
    }
}
