package com.example.minutewatcher.feature.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.minutewatcher.core.model.AppTheme
import com.example.minutewatcher.core.resources.R
import com.example.minutewatcher.feature.main.ui.components.AdjustmentComposeDialog
import com.example.minutewatcher.feature.main.ui.components.DayPickerComposeDialog
import com.example.minutewatcher.feature.main.ui.components.LanguageMenu
import com.example.minutewatcher.feature.main.ui.components.OnboardingDialog
import com.example.minutewatcher.feature.main.ui.components.ResetConfirmationDialog
import com.example.minutewatcher.feature.main.ui.components.SimCardItem
import com.example.minutewatcher.feature.main.ui.components.SimLimitComposeDialog
import com.example.minutewatcher.feature.main.ui.components.SimpleMessageDialog
import com.example.minutewatcher.feature.main.ui.components.ThemeMenu
import com.example.minutewatcher.feature.main.ui.components.TrackingBanners
import com.example.minutewatcher.feature.main.ui.components.UsageAccessDialog

@Composable
fun SimCardScreen(
    viewModel: MainViewModel,
    onOpenSettings: () -> Unit,
    onAddWidget: () -> Unit,
    onThemeChange: (AppTheme) -> Unit,
    onLanguageChange: (String?) -> Unit,
    onGrantPhonePermission: () -> Unit = {},
    onShowUsageAccessDialog: () -> Unit = {},
    onOpenUsageAccessSettings: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
    onGrantNotificationPermission: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val trackingBannerState by viewModel.trackingBannerState.collectAsStateWithLifecycle()
    val highlightedSlot by viewModel.highlightedSlot.collectAsStateWithLifecycle()
    val appTheme by viewModel.appTheme.collectAsStateWithLifecycle()
    val appLanguage by viewModel.appLanguage.collectAsStateWithLifecycle()
    val showOnboardingDialog by viewModel.showOnboardingDialog.collectAsStateWithLifecycle()
    val showUsageAccessDialog by viewModel.showUsageAccessDialog.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            SimCardTopBar(
                currentTheme = appTheme,
                currentLanguage = appLanguage,
                onThemeChange = onThemeChange,
                onLanguageChange = onLanguageChange,
                onOpenSettings = onOpenSettings,
            )
        },
        floatingActionButton = { MainFab(onAddWidget = onAddWidget) },
    ) { paddingValues ->
        MainScreenContent(
            uiState = uiState,
            paddingValues = paddingValues,
            viewModel = viewModel,
            trackingBannerState = trackingBannerState,
            highlightedSlot = highlightedSlot,
            onGrantPhonePermission = onGrantPhonePermission,
            onShowUsageAccessDialog = onShowUsageAccessDialog,
            onOpenAppSettings = onOpenAppSettings,
            onGrantNotificationPermission = onGrantNotificationPermission,
        )

        DialogRenderer(
            viewModel = viewModel,
            showUsageAccessDialog = showUsageAccessDialog,
            showOnboardingDialog = showOnboardingDialog,
            onOpenUsageAccessSettings = onOpenUsageAccessSettings,
        )
    }
}

@Composable
private fun MainFab(onAddWidget: () -> Unit) {
    FloatingActionButton(onClick = onAddWidget) {
        Icon(Icons.Default.Add, contentDescription = stringResource(id = R.string.add_widget_button))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimCardTopBar(
    currentTheme: AppTheme,
    currentLanguage: String?,
    onThemeChange: (AppTheme) -> Unit,
    onLanguageChange: (String?) -> Unit,
    onOpenSettings: () -> Unit,
) {
    TopAppBar(
        title = { Text(stringResource(id = R.string.app_name)) },
        actions = {
            ThemeMenu(currentTheme = currentTheme, onThemeChange = onThemeChange)
            LanguageMenu(currentLanguage = currentLanguage, onLanguageChange = onLanguageChange)
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(id = R.string.settings_widget_title))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreenContent(
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

@Composable
private fun SimCardList(
    simCards: List<com.example.minutewatcher.core.model.SimCardUiModel>,
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
                onSetLimits = { viewModel.setLimitDialog(LimitDialogData(model.slot, model.operatorName, model.minutesLimit, model.trafficLimitMb, model.rolloverEnabled)) },
                onResetCounters = { showResetConfirmSlot = model.slot },
                onToggleAutoReset = { enabled -> viewModel.toggleAutoReset(model.slot, enabled) },
                onEditAutoResetDay = { viewModel.setDayDialog(DayDialogData(model.slot, model.autoResetDay)) },
                onAdjustMinutes = { viewModel.setAdjustDialog(AdjustDialogData(model.slot, model.minutesRemaining.toFloat(), true)) },
                onAdjustTraffic = { viewModel.setAdjustDialog(AdjustDialogData(model.slot, model.trafficRemainingGb, false)) },
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

@Composable
private fun DialogRenderer(
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

    showDayDialog?.let { data ->
        DayPickerComposeDialog(
            initialDay = data.day,
            onDismiss = { viewModel.setDayDialog(null) },
            onDaySelected = { day ->
                viewModel.updateAutoResetDay(data.slot, day)
                viewModel.setDayDialog(null)
            },
        )
    }

    showAdjustDialog?.let { data ->
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
