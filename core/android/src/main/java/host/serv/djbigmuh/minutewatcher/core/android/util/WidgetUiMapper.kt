package host.serv.djbigmuh.minutewatcher.core.android.util

import androidx.core.graphics.ColorUtils
import host.serv.djbigmuh.minutewatcher.core.model.Constants
import host.serv.djbigmuh.minutewatcher.core.model.GlobalSnapshot
import host.serv.djbigmuh.minutewatcher.core.model.WidgetLayoutMode
import host.serv.djbigmuh.minutewatcher.core.model.WidgetSettingsState
import host.serv.djbigmuh.minutewatcher.core.model.WidgetStrings
import host.serv.djbigmuh.minutewatcher.core.model.WidgetUiState
import host.serv.djbigmuh.minutewatcher.core.model.util.FontScaleProvider

object WidgetUiMapper {
    /**
     * Maps snapshot and widget settings to WidgetUiState for rendering.
     *
     * @param strings Widget strings.
     * @param snapshot Snapshot data.
     * @param state Widget settings.
     * @param operatorNames Operator names (key: slot index).
     * @param refreshFailed Data refresh error flag.
     * @return WidgetUiState for rendering.
     */
    fun mapFromSnapshot(
        strings: WidgetStrings,
        snapshot: GlobalSnapshot,
        state: WidgetSettingsState,
        operatorNames: Map<Int, String>,
        refreshFailed: Boolean = false,
    ): WidgetUiState {
        val luminance = ColorUtils.calculateLuminance(state.backgroundColor)
        val (textColor, secondaryTextColor) = WidgetColorResolver.resolveTextColors(luminance)
        val fontMultiplier = FontScaleProvider.getMultiplier(state.fontSize)

        val activeSims = snapshot.simSnapshots
        var currentLayoutMode = WidgetLayoutMode.fromValue(state.layoutMode)

        val visibleSimCount =
            activeSims.count { sim ->
                WidgetSimUiFactory.resolveShowSim(state.simStates[sim.slot], state.simListLoadFailed)
            }

        val isPreemptivelyLimited =
            visibleSimCount >= Constants.Widget.PREEMPTIVE_LAYOUT_LIMIT_SIMS &&
                currentLayoutMode == WidgetLayoutMode.SIX_LINES

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
                            strings,
                            sim,
                            simConfig,
                            operatorNames[sim.slot] ?: sim.operatorName,
                            luminance,
                            textColor,
                            secondaryTextColor,
                            fontMultiplier,
                            state.simListLoadFailed,
                        )
                    simUiState.copy(
                        layoutSpec = WidgetLayoutSpecBuilder.generateLayoutSpec(simUiState, currentLayoutMode),
                    )
                }

        return WidgetUiState(
            layoutMode = currentLayoutMode.value,
            simArrangement = state.simArrangement,
            backgroundColor = state.backgroundColor,
            transparency = state.transparency,
            simUiStates = simUiStates,
            isHorizontal = state.simArrangement == Constants.Widget.SIM_ARRANGEMENT_HORIZONTAL,
            showSeparator =
                simUiStates.size >= Constants.Widget.MIN_SIMS_FOR_SEPARATOR &&
                    !state.widgetLimitExceeded,
            useSystemColors = state.transparency > Constants.Widget.TRANSPARENCY_SYSTEM_COLORS_THRESHOLD,
            settingsLoadFailed = state.settingsLoadFailed,
            simListLoadFailed = state.simListLoadFailed,
            refreshFailed = refreshFailed,
            isSnapshotEmpty = snapshot.timestamp == Constants.Data.EMPTY_TIMESTAMP,
            strings = strings,
        )
    }
}
