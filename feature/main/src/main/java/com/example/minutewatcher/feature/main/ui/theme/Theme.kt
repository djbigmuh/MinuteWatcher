package com.example.minutewatcher.feature.main.ui.theme

import android.os.Build
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
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.util.UsageColors
import com.example.minutewatcher.core.model.util.UsageStatus

private val DarkColorScheme =
    darkColorScheme(
        primary = Purple80,
        secondary = PurpleGrey80,
        tertiary = Pink80,
    )

private val LightColorScheme =
    lightColorScheme(
        primary = Purple40,
        secondary = PurpleGrey40,
        tertiary = Pink40,
    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
     */
    )

@Composable
fun MinuteWatcherTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
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

val ColorScheme.usageNormal: Color
    get() = Color(UsageColors.normal(background.luminance() < Constants.UI.LUMINANCE_THRESHOLD))

val ColorScheme.usageLow: Color
    get() = Color(UsageColors.low(background.luminance() < Constants.UI.LUMINANCE_THRESHOLD))

val ColorScheme.usageCritical: Color
    get() = Color(UsageColors.critical(background.luminance() < Constants.UI.LUMINANCE_THRESHOLD))

fun ColorScheme.usageColor(status: UsageStatus): Color =
    when (status) {
        UsageStatus.NORMAL -> usageNormal
        UsageStatus.LOW -> usageLow
        UsageStatus.CRITICAL -> usageCritical
    }
