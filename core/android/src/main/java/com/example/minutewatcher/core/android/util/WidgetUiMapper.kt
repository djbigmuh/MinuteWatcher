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
    ): WidgetUiState {
        val luminance = ColorUtils.calculateLuminance(state.backgroundColor)
        val (textColor, secondaryTextColor) = WidgetColorResolver.resolveTextColors(luminance)
        val fontMultiplier = FontScaleProvider.getMultiplier(state.fontSize)
        val layoutMode =
            if (state.widgetLimitExceeded) {
                WidgetLayoutMode.SIMPLE
            } else {
                WidgetLayoutMode.fromValue(state.layoutMode)
            }

        val simUiStates =
            snapshot.simSnapshots
                .filter { it.isActive }
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
                    simUiState.copy(layoutSpec = WidgetLayoutSpecBuilder.generateLayoutSpec(simUiState, layoutMode))
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
            forceSimpleLayout = state.widgetLimitExceeded,
            settingsLoadFailed = state.settingsLoadFailed,
        )
    }
}
