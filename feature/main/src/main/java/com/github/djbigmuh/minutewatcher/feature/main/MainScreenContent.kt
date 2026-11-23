package com.github.djbigmuh.minutewatcher.feature.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.github.djbigmuh.minutewatcher.core.common.util.AppLogger
import com.github.djbigmuh.minutewatcher.feature.main.ui.components.TrackingBanners
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first

@Composable
fun MainScreenContent(
    uiState: SimCardListState,
    paddingValues: PaddingValues,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    trackingBannerState: TrackingBannerState,
    trackingBannerCallbacks: TrackingBannerCallbacks,
    highlightedSlot: Int?,
    actions: SimCardScreenActions,
    onRetrySimDiscovery: () -> Unit,
    onRetrySimListAndRefresh: () -> Unit,
    onSetLimitDialog: (LimitDialogData) -> Unit,
    onResetUsage: (slot: Int) -> Unit,
    onToggleAutoReset: (slot: Int, enabled: Boolean) -> Unit,
    onSetDayDialog: (DayDialogData) -> Unit,
    onSetAdjustDialog: (AdjustDialogData) -> Unit,
    onShowTrafficInfo: () -> Unit,
    listState: LazyListState = rememberLazyListState(),
) {
    val currentUiState by rememberUpdatedState(uiState)

    // Wait for the highlighted slot to appear in the loaded data (including gradual SIM discovery).
    // Scroll once and finish the effect. Wait terminates automatically if highlightedSlot is reset.
    LaunchedEffect(highlightedSlot) {
        val slot = highlightedSlot ?: return@LaunchedEffect

        snapshotFlow { currentUiState }
            .filterIsInstance<SimCardListState.Success>()
            .first { state -> state.simCards.any { it.slot == slot } }
            .let { state ->
                val index = state.simCards.indexOfFirst { it.slot == slot }
                AppLogger.d("MainScreenContent: scrolling to highlighted slot $slot at index $index")
                listState.animateScrollToItem(index)
            }
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier =
            Modifier
                .fillMaxSize()
                .padding(paddingValues),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TrackingBanners(
                state = trackingBannerState,
                callbacks = trackingBannerCallbacks,
            )

            Box(modifier = Modifier.weight(1f)) {
                SimCardListContent(
                    uiState = uiState,
                    listState = listState,
                    highlightedSlot = highlightedSlot,
                    onGrantPhonePermission = actions.onGrantPhonePermission,
                    onRetrySimDiscovery = onRetrySimDiscovery,
                    onRetrySimListAndRefresh = onRetrySimListAndRefresh,
                    onSetLimitDialog = onSetLimitDialog,
                    onResetUsage = onResetUsage,
                    onToggleAutoReset = onToggleAutoReset,
                    onSetDayDialog = onSetDayDialog,
                    onSetAdjustDialog = onSetAdjustDialog,
                    onShowTrafficInfo = onShowTrafficInfo,
                )
            }
        }
    }
}

@Composable
private fun SimCardListContent(
    uiState: SimCardListState,
    listState: LazyListState,
    highlightedSlot: Int?,
    onGrantPhonePermission: () -> Unit,
    onRetrySimDiscovery: () -> Unit,
    onRetrySimListAndRefresh: () -> Unit,
    onSetLimitDialog: (LimitDialogData) -> Unit,
    onResetUsage: (slot: Int) -> Unit,
    onToggleAutoReset: (slot: Int, enabled: Boolean) -> Unit,
    onSetDayDialog: (DayDialogData) -> Unit,
    onSetAdjustDialog: (AdjustDialogData) -> Unit,
    onShowTrafficInfo: () -> Unit,
) {
    when (uiState) {
        is SimCardListState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        is SimCardListState.Empty -> EmptyState(onGrantPhonePermission)
        is SimCardListState.Error -> {
            ErrorState(
                messageRes = uiState.messageRes,
                onRetry = {
                    when (uiState.kind) {
                        SimCardListState.ErrorKind.SIM_DISCOVERY -> onRetrySimDiscovery()
                        SimCardListState.ErrorKind.REFRESH -> onRetrySimListAndRefresh()
                    }
                },
            )
        }

        is SimCardListState.Success -> {
            SimCardList(
                simCards = uiState.simCards,
                listState = listState,
                highlightedSlot = highlightedSlot,
                onSetLimitDialog = onSetLimitDialog,
                onResetUsage = onResetUsage,
                onToggleAutoReset = onToggleAutoReset,
                onSetDayDialog = onSetDayDialog,
                onSetAdjustDialog = onSetAdjustDialog,
                onShowTrafficInfo = onShowTrafficInfo,
            )
        }
    }
}
