package com.example.minutewatcher.feature.widget

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.color.ColorProvider
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.example.minutewatcher.core.domain.SettingsRepository
import com.example.minutewatcher.core.domain.SimRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.SimUiState
import com.example.minutewatcher.core.model.WidgetLayoutMode
import com.example.minutewatcher.core.model.WidgetUiState
import com.example.minutewatcher.feature.widget.util.WidgetUiMapper
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first

class MinutesGlanceWidget : GlanceAppWidget() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetEntryPoint {
        fun usageStateRepository(): UsageStateRepository

        fun settingsRepository(): SettingsRepository

        fun simRepository(): SimRepository
    }

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val uiState = getUiState(context)
        provideContent {
            GlanceTheme {
                WidgetContent(context, uiState)
            }
        }
    }

    private suspend fun getUiState(context: Context): WidgetUiState {
        val entryPoint =
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                WidgetEntryPoint::class.java,
            )
        val usageRepository = entryPoint.usageStateRepository()
        val settingsRepository = entryPoint.settingsRepository()
        val simRepository = entryPoint.simRepository()

        val snapshot = usageRepository.observeSnapshot().value
        val settings = settingsRepository.observeAppSettings().value

        val simStates = mutableListOf<com.example.minutewatcher.core.model.SimSettingsState>()
        for (i in 0..1) {
            val identity = simRepository.getIdentity(i)
            simStates.add(
                com.example.minutewatcher.core.model.SimSettingsState(
                    showSim = simRepository.getShowSim(identity).first(),
                    showIcon = simRepository.getShowSimIcon(identity).first(),
                    showName = simRepository.getShowSimName(identity).first(),
                    showMinutes = simRepository.getShowMinutes(identity).first(),
                    showMinutesLimit = simRepository.getShowMinutesLimit(identity).first(),
                    showTraffic = simRepository.getShowTraffic(identity).first(),
                    showTrafficLimit = simRepository.getShowTrafficLimit(identity).first(),
                ),
            )
        }

        return WidgetUiMapper.mapFromSnapshot(
            context,
            snapshot,
            com.example.minutewatcher.core.model.WidgetSettingsState(
                layoutMode = settings.widgetLayoutMode,
                simArrangement = settings.widgetSimArrangement,
                transparency = settings.widgetTransparency,
                fontSize = settings.widgetFontSize,
                backgroundColor = settings.widgetBackgroundColor,
                notifyLowMinutes = settings.notifyLowMinutes,
                notifyLowTraffic = settings.notifyLowTraffic,
                callTrackingEnabled = settings.callTrackingEnabled,
                simStates = simStates,
            ),
        )
    }

    @Composable
    private fun WidgetContent(
        context: Context,
        uiState: WidgetUiState,
    ) {
        val alpha = uiState.transparency / 100f
        val bgColor =
            androidx.compose.ui.graphics
                .Color(uiState.backgroundColor)
                .copy(alpha = alpha)

        Box(
            modifier =
                GlanceModifier
                    .fillMaxSize()
                    .background(ColorProvider(day = bgColor, night = bgColor))
                    .appWidgetBackground()
                    .clickable(actionStartActivity(ComponentName(context, "com.example.minutewatcher.MainActivity"))),
            contentAlignment = Alignment.Center,
        ) {
            val simUiStates = uiState.simUiStates.filter { it.showSim }
            val layoutMode = WidgetLayoutMode.fromValue(uiState.layoutMode)

            when {
                simUiStates.size >= 2 -> {
                    DualSimLayout(context, simUiStates[0], simUiStates[1], uiState)
                }
                simUiStates.isNotEmpty() -> {
                    SingleSimLayout(context, simUiStates[0], layoutMode)
                }
                else -> {
                    Text(
                        text = context.getString(com.example.minutewatcher.core.model.R.string.no_data),
                        style =
                            TextStyle(
                                fontSize = Constants.Widget.FONT_SIZE_MEDIUM.sp,
                                color =
                                    ColorProvider(
                                        day = androidx.compose.ui.graphics.Color.White,
                                        night = androidx.compose.ui.graphics.Color.White,
                                    ),
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
        val arrangement = uiState.simArrangement
        val layoutMode = WidgetLayoutMode.fromValue(uiState.layoutMode)

        if (arrangement == 0) { // Horizontal
            Row(
                modifier = GlanceModifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = GlanceModifier.fillMaxHeight().defaultWeight(), contentAlignment = Alignment.Center) {
                    SimItem(context, sim1, layoutMode)
                }
                Spacer(
                    modifier =
                        GlanceModifier
                            .width(
                                1.dp,
                            ).fillMaxHeight()
                            .background(
                                ColorProvider(
                                    day =
                                        androidx.compose.ui.graphics
                                            .Color(sim1.secondaryTextColor),
                                    night =
                                        androidx.compose.ui.graphics
                                            .Color(sim1.secondaryTextColor),
                                ),
                            ),
                )
                Box(modifier = GlanceModifier.fillMaxHeight().defaultWeight(), contentAlignment = Alignment.Center) {
                    SimItem(context, sim2, layoutMode)
                }
            }
        } else { // Vertical
            Column(
                modifier = GlanceModifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(modifier = GlanceModifier.fillMaxWidth().defaultWeight(), contentAlignment = Alignment.Center) {
                    SimItem(context, sim1, layoutMode)
                }
                Spacer(
                    modifier =
                        GlanceModifier
                            .height(
                                1.dp,
                            ).fillMaxWidth()
                            .background(
                                ColorProvider(
                                    day =
                                        androidx.compose.ui.graphics
                                            .Color(sim1.secondaryTextColor),
                                    night =
                                        androidx.compose.ui.graphics
                                            .Color(sim1.secondaryTextColor),
                                ),
                            ),
                )
                Box(modifier = GlanceModifier.fillMaxWidth().defaultWeight(), contentAlignment = Alignment.Center) {
                    SimItem(context, sim2, layoutMode)
                }
            }
        }
    }

    @Composable
    private fun SingleSimLayout(
        context: Context,
        sim: SimUiState,
        layoutMode: WidgetLayoutMode,
    ) {
        Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            SimItem(context, sim, layoutMode)
        }
    }

    @Composable
    private fun SimItem(
        context: Context,
        sim: SimUiState,
        layoutMode: WidgetLayoutMode,
    ) {
        Column(
            modifier = GlanceModifier.padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (layoutMode) {
                WidgetLayoutMode.SINGLE_LINE -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (sim.showIcon) {
                            Image(
                                provider = ImageProvider(sim.operatorIconRes),
                                contentDescription = null,
                                modifier = GlanceModifier.size(14.dp),
                            )
                            Spacer(modifier = GlanceModifier.width(4.dp))
                        }
                        if (sim.showMinutes) {
                            Text(
                                text = sim.minutesText,
                                style =
                                    TextStyle(
                                        fontSize = (11 * sim.fontMultiplier).sp,
                                        fontWeight = FontWeight.Bold,
                                        color =
                                            ColorProvider(
                                                day =
                                                    androidx.compose.ui.graphics
                                                        .Color(sim.minutesColor),
                                                night =
                                                    androidx.compose.ui.graphics
                                                        .Color(sim.minutesColor),
                                            ),
                                    ),
                            )
                            Spacer(modifier = GlanceModifier.width(2.dp))
                        }
                        if (sim.showTraffic) {
                            Text(
                                text = sim.trafficText,
                                style =
                                    TextStyle(
                                        fontSize = (11 * sim.fontMultiplier).sp,
                                        fontWeight = FontWeight.Bold,
                                        color =
                                            ColorProvider(
                                                day =
                                                    androidx.compose.ui.graphics
                                                        .Color(sim.trafficColor),
                                                night =
                                                    androidx.compose.ui.graphics
                                                        .Color(sim.trafficColor),
                                            ),
                                    ),
                            )
                        }
                    }
                }
                WidgetLayoutMode.TWO_LINES -> {
                    // Line 1: Icon & Name
                    if (sim.showIcon || sim.showName) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (sim.showIcon) {
                                Image(
                                    provider = ImageProvider(sim.operatorIconRes),
                                    contentDescription = null,
                                    modifier = GlanceModifier.size(16.dp),
                                )
                                Spacer(modifier = GlanceModifier.width(4.dp))
                            }
                            if (sim.showName) {
                                Text(
                                    text = sim.operatorName,
                                    style =
                                        TextStyle(
                                            fontSize = (Constants.Widget.FONT_SIZE_SMALL * sim.fontMultiplier).sp,
                                            color =
                                                ColorProvider(
                                                    day =
                                                        androidx.compose.ui.graphics
                                                            .Color(sim.textColor),
                                                    night =
                                                        androidx.compose.ui.graphics
                                                            .Color(sim.textColor),
                                                ),
                                        ),
                                )
                            }
                        }
                    }
                    // Line 2: Minutes and Traffic
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (sim.showMinutes) {
                            Text(
                                text = sim.minutesText,
                                style =
                                    TextStyle(
                                        fontSize = (Constants.Widget.FONT_SIZE_MEDIUM * sim.fontMultiplier).sp,
                                        fontWeight = FontWeight.Bold,
                                        color =
                                            ColorProvider(
                                                day =
                                                    androidx.compose.ui.graphics
                                                        .Color(sim.minutesColor),
                                                night =
                                                    androidx.compose.ui.graphics
                                                        .Color(sim.minutesColor),
                                            ),
                                    ),
                            )
                            Spacer(modifier = GlanceModifier.width(4.dp))
                        }
                        if (sim.showTraffic) {
                            Text(
                                text = sim.trafficText,
                                style =
                                    TextStyle(
                                        fontSize = (Constants.Widget.FONT_SIZE_MEDIUM * sim.fontMultiplier).sp,
                                        fontWeight = FontWeight.Bold,
                                        color =
                                            ColorProvider(
                                                day =
                                                    androidx.compose.ui.graphics
                                                        .Color(sim.trafficColor),
                                                night =
                                                    androidx.compose.ui.graphics
                                                        .Color(sim.trafficColor),
                                            ),
                                    ),
                            )
                        }
                    }
                }
                WidgetLayoutMode.THREE_LINES, WidgetLayoutMode.SIX_LINES -> {
                    if (sim.showIcon || sim.showName) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (sim.showIcon) {
                                Image(
                                    provider = ImageProvider(sim.operatorIconRes),
                                    contentDescription = null,
                                    modifier = GlanceModifier.size(16.dp),
                                )
                                Spacer(modifier = GlanceModifier.width(4.dp))
                            }
                            if (sim.showName) {
                                Text(
                                    text = sim.operatorName,
                                    style =
                                        TextStyle(
                                            fontSize = (Constants.Widget.FONT_SIZE_SMALL * sim.fontMultiplier).sp,
                                            color =
                                                ColorProvider(
                                                    day =
                                                        androidx.compose.ui.graphics
                                                            .Color(sim.textColor),
                                                    night =
                                                        androidx.compose.ui.graphics
                                                            .Color(sim.textColor),
                                                ),
                                        ),
                                )
                            }
                        }
                        Spacer(modifier = GlanceModifier.height(2.dp))
                    }

                    if (sim.showMinutes) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = sim.minutesText,
                                style =
                                    TextStyle(
                                        fontSize = (Constants.Widget.FONT_SIZE_MEDIUM * sim.fontMultiplier).sp,
                                        fontWeight = FontWeight.Bold,
                                        color =
                                            ColorProvider(
                                                day =
                                                    androidx.compose.ui.graphics
                                                        .Color(sim.minutesColor),
                                                night =
                                                    androidx.compose.ui.graphics
                                                        .Color(sim.minutesColor),
                                            ),
                                    ),
                            )
                            Spacer(modifier = GlanceModifier.width(2.dp))
                            Text(
                                text = context.getString(com.example.minutewatcher.core.model.R.string.minutes_abbr),
                                style =
                                    TextStyle(
                                        fontSize = (Constants.Widget.FONT_SIZE_SMALL * sim.fontMultiplier).sp,
                                        color =
                                            ColorProvider(
                                                day =
                                                    androidx.compose.ui.graphics
                                                        .Color(sim.secondaryTextColor),
                                                night =
                                                    androidx.compose.ui.graphics
                                                        .Color(sim.secondaryTextColor),
                                            ),
                                    ),
                            )
                            if (sim.showMinutesLimit) {
                                Text(
                                    text = sim.minutesLimitText,
                                    style =
                                        TextStyle(
                                            fontSize = (Constants.Widget.FONT_SIZE_SMALL * sim.fontMultiplier).sp,
                                            color =
                                                ColorProvider(
                                                    day =
                                                        androidx.compose.ui.graphics
                                                            .Color(sim.secondaryTextColor),
                                                    night =
                                                        androidx.compose.ui.graphics
                                                            .Color(sim.secondaryTextColor),
                                                ),
                                        ),
                                )
                            }
                        }
                    }

                    if (sim.showTraffic) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = sim.trafficText,
                                style =
                                    TextStyle(
                                        fontSize = (Constants.Widget.FONT_SIZE_MEDIUM * sim.fontMultiplier).sp,
                                        fontWeight = FontWeight.Bold,
                                        color =
                                            ColorProvider(
                                                day =
                                                    androidx.compose.ui.graphics
                                                        .Color(sim.trafficColor),
                                                night =
                                                    androidx.compose.ui.graphics
                                                        .Color(sim.trafficColor),
                                            ),
                                    ),
                            )
                            Spacer(modifier = GlanceModifier.width(2.dp))
                            Text(
                                text = context.getString(com.example.minutewatcher.core.model.R.string.gb_abbr),
                                style =
                                    TextStyle(
                                        fontSize = (Constants.Widget.FONT_SIZE_SMALL * sim.fontMultiplier).sp,
                                        color =
                                            ColorProvider(
                                                day =
                                                    androidx.compose.ui.graphics
                                                        .Color(sim.secondaryTextColor),
                                                night =
                                                    androidx.compose.ui.graphics
                                                        .Color(sim.secondaryTextColor),
                                            ),
                                    ),
                            )
                            if (sim.showTrafficLimit) {
                                Text(
                                    text = sim.trafficLimitText,
                                    style =
                                        TextStyle(
                                            fontSize = (Constants.Widget.FONT_SIZE_SMALL * 0.9f * sim.fontMultiplier).sp,
                                            color =
                                                ColorProvider(
                                                    day =
                                                        androidx.compose.ui.graphics
                                                            .Color(sim.secondaryTextColor),
                                                    night =
                                                        androidx.compose.ui.graphics
                                                            .Color(sim.secondaryTextColor),
                                                ),
                                        ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
