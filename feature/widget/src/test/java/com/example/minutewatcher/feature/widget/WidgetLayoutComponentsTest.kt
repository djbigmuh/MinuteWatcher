package com.example.minutewatcher.feature.widget

import com.example.minutewatcher.core.model.SimUiState
import com.example.minutewatcher.core.model.WidgetUiState
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetLayoutComponentsTest {
    internal enum class ExpectedWidgetMessageBranch {
        REFRESH_ERROR,
        SETTINGS_LOAD_ERROR,
        NO_DATA,
        SIM_CARDS,
        NO_SIM_FOUND,
    }

    internal fun resolveWidgetMainContentBranch(uiState: WidgetUiState): ExpectedWidgetMessageBranch {
        val simUiStates = uiState.simUiStates.filter { it.showSim }
        return when {
            uiState.refreshFailed && uiState.isSnapshotEmpty -> ExpectedWidgetMessageBranch.REFRESH_ERROR
            uiState.settingsLoadFailed || uiState.simListLoadFailed -> ExpectedWidgetMessageBranch.SETTINGS_LOAD_ERROR
            uiState.isSnapshotEmpty -> ExpectedWidgetMessageBranch.NO_DATA
            simUiStates.isNotEmpty() -> ExpectedWidgetMessageBranch.SIM_CARDS
            else -> ExpectedWidgetMessageBranch.NO_SIM_FOUND
        }
    }

    @Test
    fun `branch priority 1 - refreshFailed and isSnapshotEmpty returns refresh error`() {
        val uiState =
            createUiState(
                refreshFailed = true,
                isSnapshotEmpty = true,
                settingsLoadFailed = true,
            )
        val branch = resolveWidgetMainContentBranch(uiState)
        assertEquals(ExpectedWidgetMessageBranch.REFRESH_ERROR, branch)
    }

    @Test
    fun `branch priority 2 - settingsLoadFailed or simListLoadFailed returns settings load error`() {
        val uiState =
            createUiState(
                refreshFailed = false,
                isSnapshotEmpty = false,
                settingsLoadFailed = true,
            )
        val branch = resolveWidgetMainContentBranch(uiState)
        assertEquals(ExpectedWidgetMessageBranch.SETTINGS_LOAD_ERROR, branch)
    }

    @Test
    fun `branch priority 3 - isSnapshotEmpty returns no data`() {
        val uiState =
            createUiState(
                refreshFailed = false,
                isSnapshotEmpty = true,
                settingsLoadFailed = false,
                simListLoadFailed = false,
            )
        val branch = resolveWidgetMainContentBranch(uiState)
        assertEquals(ExpectedWidgetMessageBranch.NO_DATA, branch)
    }

    @Test
    fun `branch priority 4 - non empty visible SIMs returns sim cards`() {
        val simUiState = createSimUiState(showSim = true)
        val uiState =
            createUiState(
                refreshFailed = false,
                isSnapshotEmpty = false,
                simUiStates = listOf(simUiState),
            )
        val branch = resolveWidgetMainContentBranch(uiState)
        assertEquals(ExpectedWidgetMessageBranch.SIM_CARDS, branch)
    }

    @Test
    fun `branch priority 5 - loaded snapshot with empty SIMs returns no sim found`() {
        val uiState =
            createUiState(
                refreshFailed = false,
                isSnapshotEmpty = false,
                simUiStates = emptyList(),
            )
        val branch = resolveWidgetMainContentBranch(uiState)
        assertEquals(ExpectedWidgetMessageBranch.NO_SIM_FOUND, branch)
    }

    private fun createUiState(
        refreshFailed: Boolean = false,
        isSnapshotEmpty: Boolean = false,
        settingsLoadFailed: Boolean = false,
        simListLoadFailed: Boolean = false,
        simUiStates: List<SimUiState> = emptyList(),
    ) = WidgetUiState(
        layoutMode = 1,
        simArrangement = 0,
        backgroundColor = 0,
        transparency = 0,
        simUiStates = simUiStates,
        refreshFailed = refreshFailed,
        isSnapshotEmpty = isSnapshotEmpty,
        settingsLoadFailed = settingsLoadFailed,
        simListLoadFailed = simListLoadFailed,
    )

    private fun createSimUiState(showSim: Boolean) =
        SimUiState(
            slot = 0,
            operatorName = "Op",
            operatorIconRes = 0,
            showSim = showSim,
            showIcon = false,
            showName = true,
            showMinutes = true,
            minutesText = "0",
            minutesColor = 0,
            showMinutesLimit = false,
            minutesLimitText = "",
            showTraffic = true,
            trafficText = "0",
            trafficColor = 0,
            showTrafficLimit = false,
            trafficLimitText = "",
            fontMultiplier = 1.0f,
            textColor = 0,
            secondaryTextColor = 0,
        )
}
