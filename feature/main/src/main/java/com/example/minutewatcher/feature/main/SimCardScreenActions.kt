package com.example.minutewatcher.feature.main

import androidx.compose.runtime.Stable
import com.example.minutewatcher.core.model.AppTheme

@Stable
data class SimCardScreenActions(
    val onOpenSettings: () -> Unit,
    val onAddWidget: () -> Unit,
    val onThemeChange: (AppTheme) -> Unit,
    val onLanguageChange: (String?) -> Unit,
    val onGrantPhonePermission: () -> Unit,
    val onShowUsageAccessDialog: () -> Unit,
    val onOpenUsageAccessSettings: () -> Unit,
    val onOpenAppSettings: () -> Unit,
    val onGrantNotificationPermission: () -> Unit,
)
