package com.example.minutewatcher.feature.main

import androidx.compose.runtime.Stable
import com.example.minutewatcher.core.model.AppTheme

/**
 * Callback container for SIM card screen.
 * @Stable annotation guarantees Compose stability.
 * All callbacks are stable (do not change across recompositions).
 *
 * @property onOpenSettings Open widget settings screen.
 * @property onAddWidget Show widget addition instructions.
 * @property onThemeChange Change application theme.
 * @property onLanguageChange Change application language.
 * @property onGrantPhonePermission Request phone state permission (READ_PHONE_STATE).
 * @property onShowUsageAccessDialog Show usage access dialog.
 * @property onOpenUsageAccessSettings Open system usage access settings.
 * @property onOpenAppSettings Open system app settings.
 * @property onGrantNotificationPermission Request notification permission.
 */
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
    val onShowAbout: () -> Unit,
    val onEmailAuthor: () -> Unit,
)
