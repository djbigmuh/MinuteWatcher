package com.example.minutewatcher.core.android.util

import android.content.Context
import androidx.core.graphics.ColorUtils
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.WidgetLayoutMode
import com.example.minutewatcher.core.model.WidgetSettingsState
import com.example.minutewatcher.core.model.WidgetUiState
import com.example.minutewatcher.core.model.util.FontScaleProvider

object WidgetUiMapper {
    fun mapFromSnapshot(
        context: Context,
        snapshot: GlobalSnapshot,
        state: WidgetSettingsState,
        refreshFailed: Boolean = false,
    ): WidgetUiState {
        val luminance = ColorUtils.calculateLuminance(state.backgroundColor)
        val (textColor, secondaryTextColor) = WidgetColorResolver.resolveTextColors(luminance)
        val fontMultiplier = FontScaleProvider.getMultiplier(state.fontSize)

        val activeSims = snapshot.simSnapshots.filter { it.isActive }
        var currentLayoutMode = WidgetLayoutMode.fromValue(state.layoutMode)

        val isPreemptivelyLimited = activeSims.size >= 3 && currentLayoutMode == WidgetLayoutMode.SIX_LINES

        if (state.widgetLimitExceeded) {
            currentLayoutMode = WidgetLayoutMode.SIMPLE
        } else if (isPreemptivelyLimited) {
            // Pre-emptive limit to avoid RemoteViews size issues on triple SIM setups with high complexity
            currentLayoutMode = WidgetLayoutMode.THREE_LINES
        }

        val simUiStates =
            activeSims
                .sortedBy { it.slot }
                .map { sim ->
                    val simConfig = state.simStates[sim.slot]
                    val simUiState =
                        WidgetSimUiFactory.createSimUiState(
                            context,
                            sim,
                            simConfig,
                            luminance,
                            textColor,
                            secondaryTextColor,
                            fontMultiplier,
                        )
                    simUiState.copy(layoutSpec = WidgetLayoutSpecBuilder.generateLayoutSpec(simUiState, currentLayoutMode))
                }

        return WidgetUiState(
            layoutMode = state.layoutMode,
            simArrangement = state.simArrangement,
            backgroundColor = state.backgroundColor,
            transparency = state.transparency,
            simUiStates = simUiStates,
            isHorizontal = state.simArrangement == Constants.Widget.SIM_ARRANGEMENT_HORIZONTAL,
            showSeparator = simUiStates.size >= 2 && !state.widgetLimitExceeded,
            useSystemColors = state.transparency > Constants.Widget.TRANSPARENCY_SYSTEM_COLORS_THRESHOLD,
            settingsLoadFailed = state.settingsLoadFailed,
            refreshFailed = refreshFailed,
        )
    }
}
