package com.example.minutewatcher.feature.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.feature.main.ui.components.TrackingBanners
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreenContent(
    uiState: SimCardListState,
    paddingValues: PaddingValues,
    viewModel: MainViewModel,
    trackingBannerState: TrackingBannerState,
    highlightedSlot: Int?,
    actions: SimCardScreenActions,
) {
    var isRefreshing by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Task 13.2: Scroll to highlighted slot even if data is loaded later.
    LaunchedEffect(highlightedSlot) {
        val slot = highlightedSlot ?: return@LaunchedEffect

        // Wait for Success state with the requested slot
        snapshotFlow { uiState }
            .filter { it is SimCardListState.Success }
            .collectLatest { state ->
                val success = state as SimCardListState.Success
                val index = success.simCards.indexOfFirst { it.slot == slot }
                if (index >= 0) {
                    AppLogger.d("MainScreenContent: scrolling to highlighted slot $slot at index $index")
                    listState.animateScrollToItem(index)
                    // Once scrolled, we stop observing until highlightedSlot changes again.
                    return@collectLatest
                } else {
                    AppLogger.w("MainScreenContent: slot $slot not found in Success list, stopping wait")
                    // If slot not found in a successful list, we stop waiting.
                    return@collectLatest
                }
            }
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            isRefreshing = true
            viewModel.refreshSimCardsNow { isRefreshing = false }
        },
        modifier =
            Modifier
                .fillMaxSize()
                .padding(paddingValues),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TrackingBanners(
                state = trackingBannerState,
                callbacks =
                    rememberTrackingBannerCallbacks(
                        viewModel = viewModel,
                        actions = actions,
                    ),
            )

            Box(modifier = Modifier.weight(1f)) {
                SimCardListContent(
                    uiState = uiState,
                    listState = listState,
                    viewModel = viewModel,
                    onGrantPhonePermission = actions.onGrantPhonePermission,
                )
            }
        }
    }
}

@Composable
private fun rememberTrackingBannerCallbacks(
    viewModel: MainViewModel,
    actions: SimCardScreenActions,
): TrackingBannerCallbacks =
    remember(viewModel, actions) {
        TrackingBannerCallbacks(
            onGrantPhonePermission = actions.onGrantPhonePermission,
            onShowUsageAccessDialog = actions.onShowUsageAccessDialog,
            onOpenAppSettings = actions.onOpenAppSettings,
            onRetryAppSearch = viewModel::retryAppSearch,
            onDismissAppSearch = viewModel::dismissAppSearch,
            onRetryCalls = viewModel::retryCalls,
            onRetryShortcuts = viewModel::retryShortcuts,
            onRetryTraffic = viewModel::retryTraffic,
            onGrantNotificationPermission = actions.onGrantNotificationPermission,
            onDismissWidgetError = viewModel::dismissWidgetError,
            onDismissInaccurateCalls = viewModel::dismissInaccurateCalls,
            onRetryWidgetObservation = { viewModel.retryWidgetObservation() },
            onRetrySimDiscovery = { viewModel.retrySimDiscovery() },
        )
    }

@Composable
private fun SimCardListContent(
    uiState: SimCardListState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    viewModel: MainViewModel,
    onGrantPhonePermission: () -> Unit,
) {
    when (uiState) {
        is SimCardListState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        is SimCardListState.Empty -> EmptyState(onGrantPhonePermission)
        is SimCardListState.Error -> ErrorState(uiState.message, onRetry = { viewModel.refreshSimCardsNow() })
        is SimCardListState.Success -> {
            SimCardList(
                simCards = uiState.simCards,
                listState = listState,
                viewModel = viewModel,
            )
        }
    }
}
