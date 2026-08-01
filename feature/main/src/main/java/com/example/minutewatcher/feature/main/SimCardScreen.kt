package com.example.minutewatcher.feature.main

import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.minutewatcher.core.model.AppTheme

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
