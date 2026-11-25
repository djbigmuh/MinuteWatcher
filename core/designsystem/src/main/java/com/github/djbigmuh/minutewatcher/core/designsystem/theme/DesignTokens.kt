package com.github.djbigmuh.minutewatcher.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Semantic palette primitives. Prefer MaterialTheme.colorScheme in feature code. */
object MinuteWatcherPalette {
    val teal900 = Color(0xFF123B3A)
    val teal800 = Color(0xFF155452)
    val teal700 = Color(0xFF176B67)
    val teal600 = Color(0xFF21827C)
    val teal100 = Color(0xFFD5EFEB)
    val teal50 = Color(0xFFEDF7F5)
    val ink900 = Color(0xFF182625)
    val slate700 = Color(0xFF526461)
    val slate600 = Color(0xFF657572)
    val slate300 = Color(0xFFB9C8C4)
    val slate200 = Color(0xFFDCE6E2)
    val warm50 = Color(0xFFF6F8F6)
    val white = Color(0xFFFFFFFF)
    val amber600 = Color(0xFFB7791F)
    val darkBackground = Color(0xFF101A19)
    val darkSurface = Color(0xFF172321)
    val darkSurfaceVariant = Color(0xFF263633)
    val darkText = Color(0xFFE5EFEB)
    val darkMuted = Color(0xFFB2C3BD)
    val darkOutline = Color(0xFF52635E)
}

object MwShape {
    val extraSmall = 8.dp
    val small = 12.dp
    val medium = 16.dp
    val large = 20.dp
    val extraLarge = 28.dp
    val full = 100.dp
}

/** Shared dimensions for recurring component geometry. */
object MwSize {
    val usageProgressIndicatorHeight = 6.dp
}

object MwSpacing {
    val none = 0.dp
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
    val section: Dp = 28.dp
    val screenHorizontal = 20.dp
    val cardContent = 16.dp
}
