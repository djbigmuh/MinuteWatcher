package com.github.djbigmuh.minutewatcher.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

private val P = MinuteWatcherPalette

private val LightScheme = lightColorScheme(
    primary = P.teal700,
    onPrimary = P.white,
    primaryContainer = P.teal100,
    onPrimaryContainer = P.teal900,
    secondary = P.slate700,
    onSecondary = P.white,
    tertiary = P.amber600,
    background = P.warm50,
    onBackground = P.ink900,
    surface = P.white,
    onSurface = P.ink900,
    surfaceVariant = P.teal50,
    onSurfaceVariant = P.slate600,
    outline = P.slate300,
    outlineVariant = P.slate200,
    surfaceContainerLowest = P.white,
    surfaceContainerLow = Color(0xFFF1F5F2),
    surfaceContainer = Color(0xFFEAF1ED),
    surfaceContainerHigh = Color(0xFFE2EBE6),
)

private val DarkScheme = darkColorScheme(
    primary = P.teal100,
    onPrimary = P.teal900,
    primaryContainer = P.teal800,
    onPrimaryContainer = P.teal100,
    secondary = P.darkMuted,
    onSecondary = P.darkBackground,
    tertiary = Color(0xFFF0C477),
    background = P.darkBackground,
    onBackground = P.darkText,
    surface = P.darkSurface,
    onSurface = P.darkText,
    surfaceVariant = P.darkSurfaceVariant,
    onSurfaceVariant = P.darkMuted,
    outline = P.darkOutline,
    outlineVariant = Color(0xFF354743),
    surfaceContainerLowest = Color(0xFF101A19),
    surfaceContainerLow = Color(0xFF1B2926),
    surfaceContainer = Color(0xFF202F2C),
    surfaceContainerHigh = Color(0xFF293A36),
)

val MinuteWatcherTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 57.sp, lineHeight = 64.sp, letterSpacing = (-0.25).sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
)

@Composable
fun MinuteWatcherTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, typography = MinuteWatcherTypography, content = content)
}
