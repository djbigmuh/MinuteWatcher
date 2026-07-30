package com.example.minutewatcher.feature.widget

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.GlanceTheme
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.example.minutewatcher.core.domain.NavigationProvider
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.WidgetSettingsLoader
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.SimLayoutComponent
import com.example.minutewatcher.core.model.SimUiState
import com.example.minutewatcher.core.model.WidgetUiState
import com.example.minutewatcher.feature.widget.util.WidgetUiMapper
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

class MinutesGlanceWidget : GlanceAppWidget() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetEntryPoint {
        fun usageStateRepository(): UsageStateRepository

        fun widgetSettingsLoader(): WidgetSettingsLoader

        fun navigationProvider(): NavigationProvider
    }

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val uiState = getUiState(context)
        val navigationProvider = getNavigationProvider(context)
        provideContent {
            GlanceTheme {
                WidgetContent(context, uiState, navigationProvider)
            }
        }
    }

    private fun getNavigationProvider(context: Context): NavigationProvider {
        val entryPoint =
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                WidgetEntryPoint::class.java,
            )
        return entryPoint.navigationProvider()
    }

    private suspend fun getUiState(context: Context): WidgetUiState {
        val entryPoint =
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                WidgetEntryPoint::class.java,
            )
        val usageRepository = entryPoint.usageStateRepository()
        val widgetSettingsLoader = entryPoint.widgetSettingsLoader()

        var snapshot = usageRepository.observeSnapshot().value
        if (snapshot.timestamp == 0L) {
            // Attempt to force a refresh if the snapshot is empty (e.g. after reboot)
            snapshot = withTimeoutOrNull(2000.milliseconds) {
                usageRepository.refresh()
            } ?: snapshot
        }

        val settingsState = widgetSettingsLoader.load()

        return WidgetUiMapper.mapFromSnapshot(
            context,
            snapshot,
            settingsState,
        )
    }
}

@Composable
private fun WidgetContent(
    context: Context,
    uiState: WidgetUiState,
    navigationProvider: NavigationProvider,
) {
    val alpha = 1f - uiState.transparency / 100f
    val bgColor =
        androidx.compose.ui.graphics
            .Color(uiState.backgroundColor)
            .copy(alpha = alpha)

    val luminance =
        androidx.core.graphics.ColorUtils
            .calculateLuminance(uiState.backgroundColor)
    
    val noDataColor = if (uiState.useSystemColors) {
        GlanceTheme.colors.onSurface
    } else {
        val colorInt = if (luminance > 0.5f) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        val composeColor = androidx.compose.ui.graphics.Color(colorInt)
        ColorProvider(day = composeColor, night = composeColor)
    }

    Box(
        modifier =
            GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(day = bgColor, night = bgColor))
                .appWidgetBackground()
                .clickable(actionStartActivity(ComponentName(context, navigationProvider.mainActivityClassName))),
        contentAlignment = Alignment.Center,
    ) {
        val simUiStates = uiState.simUiStates.filter { it.showSim }

        when {
            simUiStates.size >= 2 -> {
                DualSimLayout(context, simUiStates[0], simUiStates[1], uiState)
            }
            simUiStates.isNotEmpty() -> {
                SingleSimLayout(context, simUiStates[0], uiState)
            }
            else -> {
                Text(
                    text = context.getString(com.example.minutewatcher.core.resources.R.string.no_data),
                    style =
                        TextStyle(
                            fontSize = Constants.Widget.FONT_SIZE_MEDIUM.sp,
                            color = noDataColor,
                        ),
                )
            }
        }
    }
}

@Composable
private fun DualSimLayout(
    context: Context,
    sim1: SimUiState,
    sim2: SimUiState,
    uiState: WidgetUiState,
) {
    if (uiState.isHorizontal) {
        Row(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = GlanceModifier.fillMaxHeight().defaultWeight(), contentAlignment = Alignment.Center) {
                SimItem(context, sim1, uiState)
            }
            if (uiState.showSeparator) {
                val separatorColor = if (uiState.useSystemColors) {
                    GlanceTheme.colors.outline
                } else {
                    val color = androidx.compose.ui.graphics
                        .Color(sim1.secondaryTextColor)
                        .copy(alpha = 0.5f)
                    ColorProvider(day = color, night = color)
                }
                Spacer(
                    modifier =
                        GlanceModifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(separatorColor),
                )
            }
            Box(modifier = GlanceModifier.fillMaxHeight().defaultWeight(), contentAlignment = Alignment.Center) {
                SimItem(context, sim2, uiState)
            }
        }
    } else {
        Column(
            modifier = GlanceModifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(modifier = GlanceModifier.fillMaxWidth().defaultWeight(), contentAlignment = Alignment.Center) {
                SimItem(context, sim1, uiState)
            }
            if (uiState.showSeparator) {
                val separatorColor = if (uiState.useSystemColors) {
                    GlanceTheme.colors.outline
                } else {
                    val color = androidx.compose.ui.graphics
                        .Color(sim1.secondaryTextColor)
                        .copy(alpha = 0.5f)
                    ColorProvider(day = color, night = color)
                }
                Spacer(
                    modifier =
                        GlanceModifier
                            .height(1.dp)
                            .fillMaxWidth()
                            .background(separatorColor),
                )
            }
            Box(modifier = GlanceModifier.fillMaxWidth().defaultWeight(), contentAlignment = Alignment.Center) {
                SimItem(context, sim2, uiState)
            }
        }
    }
}

@Composable
private fun SingleSimLayout(
    context: Context,
    sim: SimUiState,
    uiState: WidgetUiState,
) {
    Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        SimItem(context, sim, uiState)
    }
}

@Composable
private fun SimItem(
    context: Context,
    sim: SimUiState,
    uiState: WidgetUiState,
) {
    val summary =
        context.getString(
            com.example.minutewatcher.core.resources.R.string.sim_status_summary,
            sim.operatorName,
            sim.minutesText,
            sim.trafficText,
        )

    Column(
        modifier = GlanceModifier.padding(4.dp).semantics { contentDescription = summary },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        sim.layoutSpec.rows.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                row.components.forEach { component ->
                    when (component) {
                        is SimLayoutComponent.Icon -> {
                            Image(
                                provider = ImageProvider(component.iconRes),
                                contentDescription = null,
                                modifier = GlanceModifier.size(component.sizeDp.dp),
                            )
                        }
                        is SimLayoutComponent.Text -> {
                            val textColor = if (uiState.useSystemColors && component.color == sim.textColor) {
                                GlanceTheme.colors.onSurface
                            } else if (uiState.useSystemColors && component.color == sim.secondaryTextColor) {
                                GlanceTheme.colors.onSurfaceVariant
                            } else {
                                val composeColor = androidx.compose.ui.graphics.Color(component.color)
                                ColorProvider(day = composeColor, night = composeColor)
                            }

                            Text(
                                text = component.text,
                                style =
                                    TextStyle(
                                        fontSize = component.fontSizeSp.sp,
                                        fontWeight = if (component.isBold) FontWeight.Bold else FontWeight.Normal,
                                        color = textColor,
                                    ),
                            )
                        }
                        is SimLayoutComponent.Spacer -> {
                            if (component.widthDp > 0) {
                                Spacer(modifier = GlanceModifier.width(component.widthDp.dp))
                            }
                            if (component.heightDp > 0) {
                                Spacer(modifier = GlanceModifier.height(component.heightDp.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

