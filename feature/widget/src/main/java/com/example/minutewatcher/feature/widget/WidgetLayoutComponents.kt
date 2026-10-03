package com.example.minutewatcher.feature.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.width
import androidx.glance.layout.wrapContentSize
import com.example.minutewatcher.core.android.util.WidgetColorResolver
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.NavigationProvider
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.SimUiState
import com.example.minutewatcher.core.model.WidgetUiState
import com.example.minutewatcher.core.resources.R
import android.graphics.Color as AndroidColor
import androidx.glance.unit.ColorProvider as IColorProvider

/**
 * Renders widget content.
 * Contains background, SIM card list, and stale data indicator.
 *
 * @param uiState Widget state.
 * @param navigationProvider Navigation provider for widget clicks.
 */
@Composable
internal fun WidgetContent(
    uiState: WidgetUiState,
    navigationProvider: NavigationProvider?,
) {
    val context = LocalContext.current
    val alpha = Constants.Widget.ALPHA_FULL - (uiState.transparency / Constants.Widget.PERCENT_MAX_FLOAT)
    val bgColor = Color(uiState.backgroundColor).copy(alpha = alpha)

    val noDataColor = getNoDataColor(uiState)

    Box(
        modifier = getWidgetModifier(context, navigationProvider, bgColor),
        contentAlignment = Alignment.Center,
    ) {
        WidgetMainContent(uiState, noDataColor)
    }
}

/**
 * Renders main widget content.
 * Displays error message, no-SIM message,
 * or SIM card list depending on state.
 *
 * @param uiState Widget state.
 * @param noDataColor Color for "No data" message.
 */
@Composable
internal fun WidgetMainContent(
    uiState: WidgetUiState,
    noDataColor: IColorProvider,
) {
    val context = LocalContext.current
    val simUiStates = uiState.simUiStates.filter { it.showSim }

    when {
        uiState.refreshFailed && uiState.isSnapshotEmpty -> {
            WidgetMessage(context.getString(R.string.refresh_error), noDataColor)
        }

        uiState.settingsLoadFailed || uiState.simListLoadFailed -> {
            WidgetMessage(context.getString(R.string.settings_load_error), noDataColor)
        }

        simUiStates.isNotEmpty() -> {
            SimCardsLayout(simUiStates, uiState, noDataColor)
        }

        else -> {
            WidgetMessage(context.getString(R.string.no_sim_found), noDataColor)
        }
    }
}

/**
 * Renders SIM card list in widget.
 * Shows stale data indicator on refreshFailed.
 * Contains SIM card list via MultiSimLayout.
 *
 * @param simUiStates List of SIM card states.
 * @param uiState Widget state.
 * @param noDataColor Color for "No data" message.
 */
@Composable
internal fun SimCardsLayout(
    simUiStates: List<SimUiState>,
    uiState: WidgetUiState,
    noDataColor: IColorProvider,
) {
    Column(
        modifier = GlanceModifier.wrapContentSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (uiState.refreshFailed) {
            StaleDataIndicator(noDataColor)
        }
        Box(
            modifier = GlanceModifier.wrapContentSize(),
            contentAlignment = Alignment.Center,
        ) {
            MultiSimLayout(simUiStates, uiState)
        }
    }
}

/**
 * Renders multiple SIM cards in widget.
 * Uses HorizontalSimLayout or VerticalSimLayout
 * depending on isHorizontal.
 *
 * @param sims List of SIM card states.
 * @param uiState Widget state.
 */
@Composable
internal fun MultiSimLayout(
    sims: List<SimUiState>,
    uiState: WidgetUiState,
) {
    if (uiState.isHorizontal) {
        HorizontalSimLayout(sims, uiState)
    } else {
        VerticalSimLayout(sims, uiState)
    }
}

/**
 * Renders SIM cards horizontally (in a row).
 * Each SIM card takes equal width (defaultWeight).
 * Separator is displayed between SIM cards when showSeparator is true.
 *
 * @param sims List of SIM card states.
 * @param uiState Widget state.
 */
@Composable
internal fun HorizontalSimLayout(
    sims: List<SimUiState>,
    uiState: WidgetUiState,
) {
    Row(
        modifier = GlanceModifier.wrapContentSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        sims.forEachIndexed { index, sim ->
            Box(
                modifier = GlanceModifier.defaultWeight(),
                contentAlignment = Alignment.Center,
            ) {
                SimItem(sim, uiState)
            }
            if (
                uiState.showSeparator && index < sims.size - 1
            ) {
                SimSeparator(uiState, isHorizontal = true)
            }
        }
    }
}

/**
 * Renders SIM cards vertically (in a column).
 * Each SIM card takes equal height (defaultWeight).
 * Separator is displayed between SIM cards when showSeparator is true.
 *
 * @param sims List of SIM card states.
 * @param uiState Widget state.
 */
@Composable
internal fun VerticalSimLayout(
    sims: List<SimUiState>,
    uiState: WidgetUiState,
) {
    Column(
        modifier = GlanceModifier.wrapContentSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        sims.forEachIndexed { index, sim ->
            Box(
                modifier = GlanceModifier.defaultWeight(),
                contentAlignment = Alignment.Center,
            ) {
                SimItem(sim, uiState)
            }
            if (
                uiState.showSeparator && index < sims.size - 1
            ) {
                SimSeparator(uiState, isHorizontal = false)
            }
        }
    }
}

/**
 * Renders separator between SIM cards in widget.
 * Displayed when showSeparator = true and multiple SIMs are present.
 *
 * @param uiState Widget state.
 * @param isHorizontal true if separator is vertical (for horizontal layout).
 */
@Composable
internal fun SimSeparator(
    uiState: WidgetUiState,
    isHorizontal: Boolean,
) {
    val separatorColor =
        if (uiState.useSystemColors) {
            GlanceTheme.colors.outline
        } else {
            val color =
                Color(uiState.simUiStates.firstOrNull()?.secondaryTextColor ?: AndroidColor.GRAY)
                    .copy(alpha = Constants.Widget.SEPARATOR_ALPHA)
            ColorProvider(day = color, night = color)
        }

    val modifier =
        if (isHorizontal) {
            GlanceModifier
                .width(Constants.Widget.SEPARATOR_THICKNESS.dp)
                .fillMaxHeight()
        } else {
            GlanceModifier
                .height(Constants.Widget.SEPARATOR_THICKNESS.dp)
                .fillMaxWidth()
        }

    Spacer(modifier = modifier.background(separatorColor))
}

/**
 * Returns color for "No data" message.
 * When useSystemColors is true, returns GlanceTheme.colors.onSurface.
 * Otherwise returns color computed via WidgetColorResolver.
 *
 * @param uiState Widget state.
 * @return Color for "No data" message.
 */
@Composable
internal fun getNoDataColor(uiState: WidgetUiState): IColorProvider {
    if (uiState.useSystemColors) {
        return GlanceTheme.colors.onSurface
    }

    val colorInt = WidgetColorResolver.resolveNoDataColor(uiState.backgroundColor)
    val composeColor = Color(colorInt)
    return ColorProvider(day = composeColor, night = composeColor)
}

/**
 * Creates modifier for widget.
 * Contains background, appWidgetBackground, and clickable for widget clicks.
 *
 * @param context Context.
 * @param navigationProvider Navigation provider for widget clicks.
 * @param bgColor Background color.
 * @return Modifier for widget.
 */
internal fun getWidgetModifier(
    context: Context,
    navigationProvider: NavigationProvider?,
    bgColor: Color,
): GlanceModifier {
    var modifier =
        GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(day = bgColor, night = bgColor))
            .appWidgetBackground()

    val className = navigationProvider?.mainActivityClassName.orEmpty()
    if (className.isNotEmpty()) {
        try {
            val launchIntent =
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = ComponentName(context, className)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            modifier = modifier.clickable(actionStartActivity(launchIntent))
        } catch (e: Exception) {
            AppLogger.e("Failed to attach click action to widget", e)
        }
    }
    return modifier
}
