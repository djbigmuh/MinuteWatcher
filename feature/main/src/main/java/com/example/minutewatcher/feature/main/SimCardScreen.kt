package com.example.minutewatcher.feature.main

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
import com.example.minutewatcher.core.android.ui.onHorizontalSwipe
import com.example.minutewatcher.core.android.ui.resolveNavigationSwipeDirection
import com.example.minutewatcher.core.model.Constants

@Composable
fun SimCardScreen(
    viewModel: MainViewModel,
    actions: SimCardScreenActions,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val trackingBannerState by viewModel.trackingBannerState.collectAsStateWithLifecycle()
    val highlightedSlot by viewModel.highlightedSlot.collectAsStateWithLifecycle()
    val appTheme by viewModel.appTheme.collectAsStateWithLifecycle()
    val appLanguage by viewModel.appLanguage.collectAsStateWithLifecycle()
    val showOnboardingDialog by viewModel.showOnboardingDialog.collectAsStateWithLifecycle()
    val showUsageAccessDialog by viewModel.showUsageAccessDialog.collectAsStateWithLifecycle()
    val showAboutDialog by viewModel.showAboutDialog.collectAsStateWithLifecycle()

    SimCardErrorHost(viewModel = viewModel)

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
        snackbarHost = { SnackbarHost(hostState = remember { SnackbarHostState() }) },
    ) { paddingValues ->
        MainScreenContent(
            uiState = uiState,
            paddingValues = paddingValues,
            viewModel = viewModel,
            trackingBannerState = trackingBannerState,
            highlightedSlot = highlightedSlot,
            actions = actions,
        )

        DialogRenderer(
            viewModel = viewModel,
            showUsageAccessDialog = showUsageAccessDialog,
            showOnboardingDialog = showOnboardingDialog,
            showAboutDialog = showAboutDialog,
            onOpenUsageAccessSettings = actions.onOpenUsageAccessSettings,
            onEmailAuthor = actions.onEmailAuthor,
        )
    }
}

@Composable
private fun SimCardErrorHost(viewModel: MainViewModel) {
    val lastOperationError by viewModel.lastOperationError.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    lastOperationError?.let { error ->
        @Suppress("SpreadOperator")
        val message = stringResource(error.messageRes, *error.args.toTypedArray())
        LaunchedEffect(error) {
            snackbarHostState.showSnackbar(
                message = message,
                withDismissAction = true,
            )
            viewModel.dismissOperationError()
        }
    }
}
