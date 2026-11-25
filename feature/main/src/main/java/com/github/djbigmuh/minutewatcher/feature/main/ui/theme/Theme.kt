package com.github.djbigmuh.minutewatcher.feature.main.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import com.github.djbigmuh.minutewatcher.core.model.AppTheme
import com.github.djbigmuh.minutewatcher.core.model.Constants
import com.github.djbigmuh.minutewatcher.core.model.util.UsageColors
import com.github.djbigmuh.minutewatcher.core.model.util.UsageStatus

private val DarkColorScheme =
    darkColorScheme(
        primary = Teal100,
        onPrimary = Teal900,
        primaryContainer = Teal800,
        onPrimaryContainer = Teal100,
        secondary = DarkMuted,
        tertiary = Color(0xFFF0C477),
        background = DarkBackground,
        onBackground = DarkText,
        surface = DarkSurface,
        onSurface = DarkText,
        surfaceVariant = DarkSurfaceVariant,
        onSurfaceVariant = DarkMuted,
        outline = DarkOutline,
        outlineVariant = Color(0xFF354743),
    )

private val LightColorScheme =
    lightColorScheme(
        primary = Teal700,
        onPrimary = White,
        primaryContainer = Teal100,
        onPrimaryContainer = Teal900,
        secondary = Slate700,
        onSecondary = White,
        tertiary = Amber600,
        background = Warm50,
        onBackground = Ink900,
        surface = White,
        onSurface = Ink900,
        surfaceVariant = Teal50,
        onSurfaceVariant = Slate600,
        outline = Slate300,
        outlineVariant = Slate200,
    )

@Composable
fun MinuteWatcherTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Keep a recognizable MinuteWatcher identity by default; users can still opt into system dynamic colors.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            darkTheme -> DarkColorScheme
            else -> LightColorScheme
        }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}

/**
 * Maps [AppTheme] to a boolean indicating if dark theme should be used.
 * Respects system settings when [AppTheme.FOLLOW_SYSTEM] is selected.
 */
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
