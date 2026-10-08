package com.example.minutewatcher.core.model.util

import com.example.minutewatcher.core.model.SimUiState
import com.example.minutewatcher.core.model.WidgetStrings
import com.example.minutewatcher.core.model.WidgetUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class WidgetContentResolverTest {

    @Test
    fun `priority 1 - refreshFailed and isSnapshotEmpty returns RefreshError`() {
        val uiState =
            createUiState(
                refreshFailed = true,
                isSnapshotEmpty = true,
                settingsLoadFailed = true,
            )
        val content = resolveWidgetContent(uiState)
        assertEquals(WidgetContent.RefreshError, content)
    }

    @Test
    fun `priority 2 - settingsLoadFailed or simListLoadFailed returns SettingsLoadError`() {
        val uiState1 = createUiState(settingsLoadFailed = true)
        val uiState2 = createUiState(simListLoadFailed = true)

        assertEquals(WidgetContent.SettingsLoadError, resolveWidgetContent(uiState1))
        assertEquals(WidgetContent.SettingsLoadError, resolveWidgetContent(uiState2))
    }

    @Test
    fun `priority 3 - isSnapshotEmpty returns NoData`() {
        val uiState = createUiState(isSnapshotEmpty = true)
        assertEquals(WidgetContent.NoData, resolveWidgetContent(uiState))
    }

    @Test
    fun `priority 4 - visible SIMs returns Sims`() {
        val sim0 = createSimUiState(showSim = true, operatorName = "MTS")
        val sim1 = createSimUiState(showSim = false, operatorName = "Hidden")
        val sim2 = createSimUiState(showSim = true, operatorName = "Beeline")
        val uiState = createUiState(simUiStates = listOf(sim0, sim1, sim2))

        val content = resolveWidgetContent(uiState)
        assertTrue(content is WidgetContent.Sims)
        assertEquals(listOf(sim0, sim2), (content as WidgetContent.Sims).visible)
    }

    @Test
    fun `priority 5 - no visible SIMs returns NoSimFound`() {
        val sim0 = createSimUiState(showSim = false)
        val uiState = createUiState(simUiStates = listOf(sim0), isSnapshotEmpty = false)
        assertEquals(WidgetContent.NoSimFound, resolveWidgetContent(uiState))
    }

    @Test
    fun `buildSimSummary formats correctly when format is present`() {
        val strings = WidgetStrings(
            usedText = "Used",
            minutesAbbr = "min",
            limitText = "Limit",
            gbAbbr = "GB",
            simStatusSummaryFormat = "%s | %s | %s",
            locale = Locale.US,
        )
        val sim = createSimUiState(showSim = true, operatorName = "MTS").copy(
            minutesText = "100 min",
            trafficText = "5 GB",
        )

        val summary = buildSimSummary(strings, sim)
        assertEquals("MTS | 100 min | 5 GB", summary)
    }

    @Test
    fun `buildSimSummary falls back when format is empty`() {
        val strings = WidgetStrings(
            usedText = "Used",
            minutesAbbr = "min",
            limitText = "Limit",
            gbAbbr = "GB",
            simStatusSummaryFormat = "",
        )
        val sim = createSimUiState(showSim = true, operatorName = "MTS").copy(
            minutesText = "100 min",
            trafficText = "5 GB",
        )

        val summary = buildSimSummary(strings, sim)
        assertEquals("MTS: 100 min, 5 GB", summary)
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

    private fun createSimUiState(
        showSim: Boolean,
        operatorName: String = "Op",
    ) = SimUiState(
        slot = 0,
        operatorName = operatorName,
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
