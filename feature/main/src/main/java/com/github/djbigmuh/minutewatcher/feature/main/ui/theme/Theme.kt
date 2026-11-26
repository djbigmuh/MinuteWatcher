package com.github.djbigmuh.minutewatcher.feature.main.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.github.djbigmuh.minutewatcher.core.model.AppTheme
import com.github.djbigmuh.minutewatcher.core.model.Constants
import com.github.djbigmuh.minutewatcher.core.model.util.UsageColors
import com.github.djbigmuh.minutewatcher.core.model.util.UsageStatus
import com.github.djbigmuh.minutewatcher.core.designsystem.theme.MinuteWatcherTheme as SharedMinuteWatcherTheme

/** Compatibility entry point. The actual theme implementation lives in core:designsystem. */
@Composable
fun MinuteWatcherTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    SharedMinuteWatcherTheme(darkTheme = darkTheme, dynamicColor = dynamicColor, content = content)
}

/** Maps [AppTheme] to a boolean indicating if dark theme should be used. */
@Composable
fun rememberIsDarkTheme(appTheme: AppTheme): Boolean =
    when (appTheme) {
        AppTheme.DARK -> true
        AppTheme.LIGHT -> false
        AppTheme.FOLLOW_SYSTEM -> isSystemInDarkTheme()
    }

val ColorScheme.usageNormal: Color
    get() = Color(UsageColors.normal(surface.luminance() < Constants.UI.LUMINANCE_THRESHOLD))

val ColorScheme.usageLow: Color
    get() = Color(UsageColors.low(surface.luminance() < Constants.UI.LUMINANCE_THRESHOLD))

val ColorScheme.usageCritical: Color
    get() = Color(UsageColors.critical(surface.luminance() < Constants.UI.LUMINANCE_THRESHOLD))

fun ColorScheme.usageColor(status: UsageStatus): Color =
    when (status) {
        UsageStatus.NORMAL -> usageNormal
        UsageStatus.LOW -> usageLow
        UsageStatus.CRITICAL -> usageCritical
    }
