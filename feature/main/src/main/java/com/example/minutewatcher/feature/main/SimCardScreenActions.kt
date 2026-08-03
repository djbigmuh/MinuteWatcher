package com.example.minutewatcher.feature.main

import com.example.minutewatcher.core.model.AppTheme

data class SimCardScreenActions(
    val onOpenSettings: () -> Unit = {},
    val onAddWidget: () -> Unit = {},
    val onThemeChange: (AppTheme) -> Unit = {},
    val onLanguageChange: (String?) -> Unit = {},
    val onGrantPhonePermission: () -> Unit = {},
    val onShowUsageAccessDialog: () -> Unit = {},
    val onOpenUsageAccessSettings: () -> Unit = {},
    val onOpenAppSettings: () -> Unit = {},
    val onGrantNotificationPermission: () -> Unit = {},
)
