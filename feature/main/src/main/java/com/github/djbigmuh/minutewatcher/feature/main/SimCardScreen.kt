package com.github.djbigmuh.minutewatcher.feature.main

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.djbigmuh.minutewatcher.core.android.ui.onHorizontalSwipe
import com.github.djbigmuh.minutewatcher.core.android.ui.resolveNavigationSwipeDirection
import com.github.djbigmuh.minutewatcher.core.model.Constants
import com.github.djbigmuh.minutewatcher.feature.main.controller.OperationError

@Composable
fun SimCardScreen(
    viewModel: MainViewModel,
    actions: SimCardScreenActions,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val trackingBannerState by viewModel.trackingBannerState.collectAsStateWithLifecycle()
    val highlightedSlot by viewModel.highlightedSlot.collectAsStateWithLifecycle()
    val appTheme by viewModel.appTheme.collectAsStateWithLifecycle()
    val appLanguage by viewModel.appLanguage.collectAsStateWithLifecycle()
    val showLimitDialog by viewModel.showLimitDialog.collectAsStateWithLifecycle()
    val showDayDialog by viewModel.showDayDialog.collectAsStateWithLifecycle()
    val showAdjustDialog by viewModel.showAdjustDialog.collectAsStateWithLifecycle()
    val showTrafficLimitationDialog by viewModel.showTrafficLimitationDialog.collectAsStateWithLifecycle()
    val showOnboardingDialog by viewModel.showOnboardingDialog.collectAsStateWithLifecycle()
    val showUsageAccessDialog by viewModel.showUsageAccessDialog.collectAsStateWithLifecycle()
    val showAboutDialog by viewModel.showAboutDialog.collectAsStateWithLifecycle()
    val lastOperationError by viewModel.lastOperationError.collectAsStateWithLifecycle()

    val trackingBannerCallbacks =
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
                onRetryWidgetObservation = viewModel::retryWidgetObservation,
                onRetrySimDiscovery = viewModel::retrySimDiscovery,
            )
        }

    val snackbarHostState = remember { SnackbarHostState() }

    SimCardErrorHost(
        snackbarHostState = snackbarHostState,
        lastOperationError = lastOperationError,
        onDismissOperationError = viewModel::dismissOperationError,
    )

    Scaffold(
        modifier =
            Modifier
                .fillMaxSize()
                .onHorizontalSwipe(
                    resolveNavigationSwipeDirection(
                        navigateForward = true,
                        isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl,
                    ),
                    Constants.UI.SWIPE_NAVIGATION_THRESHOLD_DP.dp,
                ) { actions.onOpenSettings() },
        topBar = {
            SimCardTopBar(
                currentTheme = appTheme,
                currentLanguage = appLanguage,
                onThemeChange = actions.onThemeChange,
                onLanguageChange = actions.onLanguageChange,
                onOpenSettings = actions.onOpenSettings,
                onShowAbout = actions.onShowAbout,
            )
        },
        floatingActionButton = { MainFab(onAddWidget = actions.onAddWidget) },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { paddingValues ->
        MainScreenContent(
            uiState = uiState,
            paddingValues = paddingValues,
            isRefreshing = isRefreshing,
            onRefresh = viewModel::refreshSimCardsNow,
            trackingBannerState = trackingBannerState,
            trackingBannerCallbacks = trackingBannerCallbacks,
            highlightedSlot = highlightedSlot,
            actions = actions,
            onRetrySimDiscovery = viewModel::retrySimDiscovery,
            onRetrySimListAndRefresh = {
                viewModel.retrySimList()
                viewModel.refreshSimCardsNow()
            },
            onSetLimitDialog = viewModel::setLimitDialog,
            onResetUsage = viewModel::resetUsage,
            onToggleAutoReset = viewModel::toggleAutoReset,
            onSetDayDialog = viewModel::setDayDialog,
            onSetAdjustDialog = viewModel::setAdjustDialog,
            onShowTrafficInfo = { viewModel.setTrafficLimitationDialog(true) },
        )

        DialogRenderer(
            showLimitDialog = showLimitDialog,
            showDayDialog = showDayDialog,
            showAdjustDialog = showAdjustDialog,
            showTrafficLimitationDialog = showTrafficLimitationDialog,
            showUsageAccessDialog = showUsageAccessDialog,
            showOnboardingDialog = showOnboardingDialog,
            showAboutDialog = showAboutDialog,
            versionName = viewModel.versionName,
            versionCode = viewModel.versionCode,
            onDismissLimitDialog = { viewModel.setLimitDialog(null) },
            onConfirmLimits = { slot, min, traf, rollover ->
                viewModel.updateLimits(slot, min, traf, rollover)
                viewModel.setLimitDialog(null)
            },
            onResetLimits = { slot ->
                viewModel.updateLimits(slot, 0, 0, false)
                viewModel.setLimitDialog(null)
            },
            onDismissDayDialog = { viewModel.setDayDialog(null) },
            onConfirmAutoResetDay = { slot, day ->
                viewModel.updateAutoResetDay(slot, day)
                viewModel.setDayDialog(null)
            },
            onDismissAdjustDialog = { viewModel.setAdjustDialog(null) },
            onConfirmAdjustMinutes = { slot, value ->
                viewModel.adjustRemainingMinutes(slot, value)
                viewModel.setAdjustDialog(null)
            },
            onConfirmAdjustTraffic = { slot, value ->
                viewModel.adjustRemainingTraffic(slot, value)
                viewModel.setAdjustDialog(null)
            },
            onDismissTrafficLimitationDialog = { viewModel.setTrafficLimitationDialog(false) },
            onDismissUsageAccessDialog = { viewModel.setUsageAccessDialog(false) },
            onOpenUsageAccessSettings = actions.onOpenUsageAccessSettings,
            onCompleteOnboarding = viewModel::completeOnboarding,
            onDismissAboutDialog = { viewModel.setShowAboutDialog(false) },
            onEmailAuthor = actions.onEmailAuthor,
        )
    }
}

@Composable
internal fun SimCardErrorHost(
    snackbarHostState: SnackbarHostState,
    lastOperationError: OperationError?,
    onDismissOperationError: () -> Unit,
) {
    lastOperationError?.let { error ->
        val message =
            when {
                error.args.isEmpty() -> stringResource(error.messageRes)
                error.args.size == 1 -> stringResource(error.messageRes, error.args[0])
                else -> stringResource(error.messageRes, error.args[0], error.args[1])
            }
        LaunchedEffect(error) {
            snackbarHostState.showSnackbar(
                message = message,
                withDismissAction = true,
            )
            onDismissOperationError()
        }
    }
}
