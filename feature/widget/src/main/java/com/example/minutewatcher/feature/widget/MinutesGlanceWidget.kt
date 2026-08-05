package com.example.minutewatcher.feature.widget

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.provideContent
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
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.example.minutewatcher.core.android.ui.SimLayoutRenderer
import com.example.minutewatcher.core.android.util.WidgetUiMapper
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.NavigationProvider
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.WidgetSettingsLoader
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.SimUiState
import com.example.minutewatcher.core.model.WidgetSettingsState
import com.example.minutewatcher.core.model.WidgetUiState
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
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
                WidgetContent(uiState, navigationProvider)
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
        var refreshFailed = false
        if (snapshot.timestamp == 0L) {
            // Attempt to force a refresh if the snapshot is empty (e.g. after reboot)
            try {
                val result = withTimeoutOrNull(Constants.Data.SNAPSHOT_TIMEOUT_MS.milliseconds) {
                    usageRepository.refresh()
                }
                if (result != null) {
                    snapshot = result.snapshot
                    if (snapshot.timestamp == 0L) {
                        refreshFailed = true
                    }
                } else {
                    refreshFailed = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Widget refresh failed", e)
                refreshFailed = true
            }
        }

        val settingsState =
            try {
                widgetSettingsLoader.load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Failed to load widget settings, using defaults", e)
                WidgetSettingsState(settingsLoadFailed = true)
            }

        return WidgetUiMapper.mapFromSnapshot(
            context,
            snapshot,
            settingsState,
            refreshFailed = refreshFailed,
        )
    }
}

@Composable
private fun WidgetContent(
    uiState: WidgetUiState,
    navigationProvider: NavigationProvider,
) {
    val context = LocalContext.current
    val alpha = Constants.Widget.ALPHA_FULL - uiState.transparency / Constants.Widget.PERCENT_MAX_FLOAT
    val bgColor =
        androidx.compose.ui.graphics
            .Color(uiState.backgroundColor)
            .copy(alpha = alpha)

    val luminance =
        androidx.core.graphics.ColorUtils
            .calculateLuminance(uiState.backgroundColor)

    val noDataColor =
        if (uiState.useSystemColors) {
            GlanceTheme.colors.onSurface
        } else {
            val colorInt =
                if (luminance >
                    Constants.UI.LUMINANCE_THRESHOLD
                ) {
                    android.graphics.Color.BLACK
                } else {
                    android.graphics.Color.WHITE
                }
            val composeColor =
                androidx.compose.ui.graphics
                    .Color(colorInt)
            ColorProvider(day = composeColor, night = composeColor)
        }

    Box(
        modifier =
            run {
                var modifier =
                    GlanceModifier
                        .fillMaxSize()
                        .background(ColorProvider(day = bgColor, night = bgColor))
                        .appWidgetBackground()

                val className = navigationProvider.mainActivityClassName
                if (className.isNotEmpty()) {
                    try {
                        modifier = modifier.clickable(actionStartActivity(ComponentName(context, className)))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.e("Failed to attach click action to widget", e)
                    }
                }
                modifier
            },
        contentAlignment = Alignment.Center,
    ) {
        val simUiStates = uiState.simUiStates.filter { it.showSim }

        if (uiState.settingsLoadFailed) {
            Text(
                text = context.getString(com.example.minutewatcher.core.resources.R.string.settings_load_error),
                style =
                    TextStyle(
                        fontSize = Constants.Widget.FONT_SIZE_MEDIUM.sp,
                        color = noDataColor,
                    ),
            )
        } else if (uiState.refreshFailed) {
            Text(
                text = context.getString(com.example.minutewatcher.core.resources.R.string.refresh_error),
                style =
                    TextStyle(
                        fontSize = Constants.Widget.FONT_SIZE_MEDIUM.sp,
                        color = noDataColor,
                    ),
            )
        } else if (simUiStates.isEmpty()) {
            Text(
                text = context.getString(com.example.minutewatcher.core.resources.R.string.no_data),
                style =
                    TextStyle(
                        fontSize = Constants.Widget.FONT_SIZE_MEDIUM.sp,
                        color = noDataColor,
                    ),
            )
        } else {
            MultiSimLayout(simUiStates, uiState)
        }
    }
}

@Composable
private fun MultiSimLayout(
    sims: List<SimUiState>,
    uiState: WidgetUiState,
) {
    if (uiState.isHorizontal) {
        Row(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            sims.forEachIndexed { index, sim ->
                Box(modifier = GlanceModifier.fillMaxHeight().defaultWeight(), contentAlignment = Alignment.Center) {
                    SimItem(sim, uiState)
                }
                if (uiState.showSeparator && index < sims.size - 1) {
                    val separatorColor =
                        if (uiState.useSystemColors) {
                            GlanceTheme.colors.outline
                        } else {
                            val color =
                                androidx.compose.ui.graphics
                                    .Color(sims[0].secondaryTextColor)
                                    .copy(alpha = Constants.Widget.SEPARATOR_ALPHA)
                            ColorProvider(day = color, night = color)
                        }
                    Spacer(
                        modifier =
                            GlanceModifier
                                .width(Constants.Widget.SEPARATOR_THICKNESS.dp)
                                .fillMaxHeight()
                                .background(separatorColor),
                    )
                }
            }
        }
    } else {
        Column(
            modifier = GlanceModifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            sims.forEachIndexed { index, sim ->
                Box(modifier = GlanceModifier.fillMaxWidth().defaultWeight(), contentAlignment = Alignment.Center) {
                    SimItem(sim, uiState)
                }
                if (uiState.showSeparator && index < sims.size - 1) {
                    val separatorColor =
                        if (uiState.useSystemColors) {
                            GlanceTheme.colors.outline
                        } else {
                            val color =
                                androidx.compose.ui.graphics
                                    .Color(sims[0].secondaryTextColor)
                                    .copy(alpha = Constants.Widget.SEPARATOR_ALPHA)
                            ColorProvider(day = color, night = color)
                        }
                    Spacer(
                        modifier =
                            GlanceModifier
                                .height(Constants.Widget.SEPARATOR_THICKNESS.dp)
                                .fillMaxWidth()
                                .background(separatorColor),
                    )
                }
            }
        }
    }
}

@Composable
private fun SimItem(
    sim: SimUiState,
    uiState: WidgetUiState,
) {
    val context = LocalContext.current
    val summary =
        context.getString(
            com.example.minutewatcher.core.resources.R.string.sim_status_summary,
            sim.operatorName,
            sim.minutesText,
            sim.trafficText,
        )

    Column(
        modifier =
            GlanceModifier
                .padding(
                    Constants.Widget.PADDING_SIM_ITEM.dp,
                ).semantics { contentDescription = summary },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SimLayoutRenderer.Render(
            spec = sim.layoutSpec,
            rowContainer = { content ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    content()
                }
            },
            iconRenderer = { component ->
                Image(
                    provider = ImageProvider(component.iconRes),
                    contentDescription = null,
                    modifier = GlanceModifier.size(component.sizeDp.dp),
                )
            },
            textRenderer = { component ->
                val textColor =
                    if (uiState.useSystemColors && component.color == sim.textColor) {
                        GlanceTheme.colors.onSurface
                    } else if (uiState.useSystemColors && component.color == sim.secondaryTextColor) {
                        GlanceTheme.colors.onSurfaceVariant
                    } else {
                        val composeColor =
                            androidx.compose.ui.graphics
                                .Color(component.color)
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
            },
            spacerRenderer = { component ->
                if (component.widthDp > 0) {
                    Spacer(modifier = GlanceModifier.width(component.widthDp.dp))
                }
                if (component.heightDp > 0) {
                    Spacer(modifier = GlanceModifier.height(component.heightDp.dp))
                }
            },
        )
    }
}
