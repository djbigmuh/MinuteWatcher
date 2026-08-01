package com.example.minutewatcher.feature.main

import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreenContent(
    uiState: SimCardListState,
    paddingValues: PaddingValues,
    viewModel: MainViewModel,
    trackingBannerState: TrackingBannerState,
    highlightedSlot: Int?,
    onGrantPhonePermission: () -> Unit,
    onShowUsageAccessDialog: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onGrantNotificationPermission: () -> Unit,
) {
    var isRefreshing by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(highlightedSlot) {
        val slot = highlightedSlot ?: return@LaunchedEffect
        val state = uiState
        if (state is SimCardListState.Success) {
            val index = state.simCards.indexOfFirst { it.slot == slot }
            if (index >= 0) listState.animateScrollToItem(index)
        }
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            isRefreshing = true
            viewModel.refreshSimCardsNow { isRefreshing = false }
        },
        modifier = Modifier.fillMaxSize().padding(paddingValues),
    ) {
        when (uiState) {
            is SimCardListState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            is SimCardListState.Empty -> EmptyState()
            is SimCardListState.Error -> ErrorState(uiState.message)
            is SimCardListState.Success -> {
                SimCardList(
                    simCards = uiState.simCards,
                    listState = listState,
                    viewModel = viewModel,
                    trackingBannerState = trackingBannerState,
                    onGrantPhonePermission = onGrantPhonePermission,
                    onShowUsageAccessDialog = onShowUsageAccessDialog,
                    onOpenAppSettings = onOpenAppSettings,
                    onGrantNotificationPermission = onGrantNotificationPermission,
                )
            }
        }
    }
}
