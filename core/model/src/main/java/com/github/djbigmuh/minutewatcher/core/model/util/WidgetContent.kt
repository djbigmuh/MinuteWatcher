package com.github.djbigmuh.minutewatcher.core.model.util

import com.github.djbigmuh.minutewatcher.core.model.SimUiState
import com.github.djbigmuh.minutewatcher.core.model.WidgetStrings
import com.github.djbigmuh.minutewatcher.core.model.WidgetUiState

/**
 * Resolved content branches for widget and widget preview.
 */
sealed interface WidgetContent {
    data object RefreshError : WidgetContent

    data object SettingsLoadError : WidgetContent

    data object NoData : WidgetContent

    data object NoSimFound : WidgetContent

    /**
     * Widget content with visible SIM UI states.
     *
     * @property visible List of visible SIM UI states.
     */
    data class Sims(
        val visible: List<SimUiState>,
    ) : WidgetContent
}

/**
 * Resolves widget content branches according to UI state priorities:
 * 1. refreshFailed && isSnapshotEmpty -> RefreshError
 * 2. settingsLoadFailed || simListLoadFailed -> SettingsLoadError
 * 3. isSnapshotEmpty -> NoData
 * 4. visible SIMs (simUiStates.filter { it.showSim }) non-empty -> Sims(visible)
 * 5. else -> NoSimFound
 */
fun resolveWidgetContent(uiState: WidgetUiState): WidgetContent {
    val visibleSims = uiState.simUiStates
    return when {
        uiState.refreshFailed && uiState.isSnapshotEmpty -> WidgetContent.RefreshError
        uiState.settingsLoadFailed || uiState.simListLoadFailed -> WidgetContent.SettingsLoadError
        uiState.isSnapshotEmpty -> WidgetContent.NoData
        visibleSims.isNotEmpty() -> WidgetContent.Sims(visibleSims)
        else -> WidgetContent.NoSimFound
    }
}

/**
 * Builds accessibility summary string for a SIM card.
 */
fun buildSimSummary(
    strings: WidgetStrings,
    sim: SimUiState,
): String =
    if (strings.simStatusSummaryFormat.isNotEmpty()) {
        try {
            String.format(
                strings.locale,
                strings.simStatusSummaryFormat,
                sim.operatorName,
                sim.minutesText,
                sim.trafficText,
            )
        } catch (_: java.util.IllegalFormatException) {
            // Format string from translations may contain incompatible specifiers or arguments;
            // fall back to unformatted representation.
            @Suppress("SwallowedException")
            "${sim.operatorName}: ${sim.minutesText}, ${sim.trafficText}"
        }
    } else {
        "${sim.operatorName}: ${sim.minutesText}, ${sim.trafficText}"
    }
