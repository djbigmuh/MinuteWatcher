package com.example.minutewatcher.feature.main.ui.components

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class SimLimitInputTest {
    private val defaultLocale = Locale.US

    @Test
    fun `minutes - old 0, types 5 - results in 5`() {
        val state = SimLimitState("0", "0.0", false, locale = defaultLocale)
        // Simulate typing '5' at the end of "0"
        state.onMinutesChange(TextFieldValue("05", selection = TextRange(2)), defaultLocale)
        assertEquals("5", state.minutesText.text)
    }

    @Test
    fun `minutes - old 0, types 0 - results in 0`() {
        val state = SimLimitState("0", "0.0", false, locale = defaultLocale)
        // Simulate typing '0' at the end of "0"
        state.onMinutesChange(TextFieldValue("00", selection = TextRange(2)), defaultLocale)
        assertEquals("0", state.minutesText.text)
    }

    @Test
    fun `minutes - old 0, full selection, types 7 - results in 7`() {
        val state = SimLimitState("0", "0.0", false, locale = defaultLocale)
        state.selectAllMinutes()
        // Simulate replacing selection "0" with "7"
        state.onMinutesChange(TextFieldValue("7", selection = TextRange(1)), defaultLocale)
        assertEquals("7", state.minutesText.text)
    }

    @Test
    fun `minutes - old 12, full selection, types 5 - results in 5`() {
        val state = SimLimitState("12", "0.0", false, locale = defaultLocale)
        state.selectAllMinutes()
        // Simulate replacing selection "12" with "5"
        state.onMinutesChange(TextFieldValue("5", selection = TextRange(1)), defaultLocale)
        assertEquals("5", state.minutesText.text)
    }

    @Test
    fun `minutes - old 0, paste 100 - results in 100`() {
        val state = SimLimitState("0", "0.0", false, locale = defaultLocale)
        // Paste "100" at the end: "0100"
        state.onMinutesChange(TextFieldValue("0100", selection = TextRange(4)), defaultLocale)
        // 0100 normalized to 100 (if it doesn't use the single-char heuristic)
        assertEquals("100", state.minutesText.text)
    }

    @Test
    fun `minutes - old 500, full selection, paste 100 - results in 100`() {
        val state = SimLimitState("500", "0.0", false, locale = defaultLocale)
        state.selectAllMinutes()
        // Replace "500" with "100"
        state.onMinutesChange(TextFieldValue("100", selection = TextRange(3)), defaultLocale)
        assertEquals("100", state.minutesText.text)
    }

    @Test
    fun `traffic - old 0_0, types 5 - results in 5`() {
        val state = SimLimitState("0", "0.0", false, locale = defaultLocale)
        // Simulate typing '5' at the end: "0.05"
        state.onTrafficChange(TextFieldValue("0.05", selection = TextRange(4)), defaultLocale)
        assertEquals("5", state.trafficText.text)
    }

    @Test
    fun `traffic - old 0_0, full selection, paste 15_5 - results in 15_5`() {
        val state = SimLimitState("0", "0.0", false, locale = defaultLocale)
        state.selectAllTraffic()
        // Replace "0.0" with "15.5"
        state.onTrafficChange(TextFieldValue("15.5", selection = TextRange(4)), defaultLocale)
        assertEquals("15.5", state.trafficText.text)
    }

    @Test
    fun `traffic - old 5, full selection, paste 500 - results in 500`() {
        val state = SimLimitState("0", "5", false, locale = defaultLocale)
        state.selectAllTraffic()
        // Replace "5" with "500"
        state.onTrafficChange(TextFieldValue("500", selection = TextRange(3)), defaultLocale)
        assertEquals("500", state.trafficText.text)
    }

    @Test
    fun `traffic - old 0, paste 10_5 - results in 10_5`() {
        val state = SimLimitState("0", "0", false, locale = defaultLocale)
        // Paste "10.5" at the end: "010.5"
        state.onTrafficChange(TextFieldValue("010.5", selection = TextRange(5)), defaultLocale)
        // Normalized: 010.5 -> 10.5
        assertEquals("10.5", state.trafficText.text)
    }

    @Test
    fun `limit exceeded - ignores input`() {
        val state = SimLimitState("0", "0", false, locale = defaultLocale)
        // Max 6 chars for minutes. Try to type 7th.
        state.onMinutesChange(TextFieldValue("1234567", selection = TextRange(7)), defaultLocale)
        assertEquals("0", state.minutesText.text)
    }

    @Test
    fun `minutes focus gained sets pending flag and selects all text`() {
        val state = SimLimitState("500", "10.0", false, locale = defaultLocale)
        state.onMinutesFocusChanged(true)

        assertTrue(state.minutesSelectAllPending)
        assertEquals(0, state.minutesText.selection.min)
        assertEquals(3, state.minutesText.selection.max)
    }

    @Test
    fun `minutes selection only event on focus absorbs click and restores full selection`() {
        val state = SimLimitState("500", "10.0", false, locale = defaultLocale)
        state.onMinutesFocusChanged(true)

        // Click-driven selection change (same text "500", collapsed cursor at position 1)
        state.onMinutesChange(TextFieldValue("500", selection = TextRange(1)), defaultLocale)

        assertFalse(state.minutesSelectAllPending)
        assertEquals(0, state.minutesText.selection.min)
        assertEquals(3, state.minutesText.selection.max)
    }

    @Test
    fun `minutes typing 7 after focus absorption replaces initial text completely`() {
        val state = SimLimitState("500", "10.0", false, locale = defaultLocale)
        state.onMinutesFocusChanged(true)
        // Absorb click
        state.onMinutesChange(TextFieldValue("500", selection = TextRange(1)), defaultLocale)

        // User types '7' replacing selection
        state.onMinutesChange(TextFieldValue("7", selection = TextRange(1)), defaultLocale)

        assertEquals("7", state.minutesText.text)
    }

    @Test
    fun `minutes text change while pending flag set processes input and resets flag`() {
        val state = SimLimitState("500", "10.0", false, locale = defaultLocale)
        state.onMinutesFocusChanged(true)

        // Direct typing/pasting without preceding selection-only event
        state.onMinutesChange(TextFieldValue("700", selection = TextRange(3)), defaultLocale)

        assertFalse(state.minutesSelectAllPending)
        assertEquals("700", state.minutesText.text)
    }

    @Test
    fun `minutes selection only event after flag cleared respects requested cursor position`() {
        val state = SimLimitState("500", "10.0", false, locale = defaultLocale)
        state.onMinutesFocusChanged(true)
        // Absorb click
        state.onMinutesChange(TextFieldValue("500", selection = TextRange(1)), defaultLocale)

        // Second click / selection change after flag is cleared
        state.onMinutesChange(TextFieldValue("500", selection = TextRange(2)), defaultLocale)

        assertEquals(2, state.minutesText.selection.start)
        assertEquals(2, state.minutesText.selection.end)
    }

    @Test
    fun `minutes insertion in middle preserves cursor position when unchanged by normalization`() {
        val state = SimLimitState("1234", "0.0", false, locale = defaultLocale)
        state.onMinutesChange(TextFieldValue("12934", selection = TextRange(3)), defaultLocale)
        assertEquals("12934", state.minutesText.text)
        assertEquals(3, state.minutesText.selection.start)
        assertEquals(3, state.minutesText.selection.end)
    }

    @Test
    fun `minutes deletion in middle preserves cursor position when unchanged by normalization`() {
        val state = SimLimitState("1234", "0.0", false, locale = defaultLocale)
        state.onMinutesChange(TextFieldValue("134", selection = TextRange(1)), defaultLocale)
        assertEquals("134", state.minutesText.text)
        assertEquals(1, state.minutesText.selection.start)
    }

    @Test
    fun `traffic focus gained sets pending flag and selects all text`() {
        val state = SimLimitState("500", "10.0", false, locale = defaultLocale)
        state.onTrafficFocusChanged(true)

        assertTrue(state.trafficSelectAllPending)
        assertEquals(0, state.trafficText.selection.min)
        assertEquals(4, state.trafficText.selection.max)
    }

    @Test
    fun `traffic selection only event on focus absorbs click and restores full selection`() {
        val state = SimLimitState("500", "10.0", false, locale = defaultLocale)
        state.onTrafficFocusChanged(true)

        // Click-driven selection change
        state.onTrafficChange(TextFieldValue("10.0", selection = TextRange(2)), defaultLocale)

        assertFalse(state.trafficSelectAllPending)
        assertEquals(0, state.trafficText.selection.min)
        assertEquals(4, state.trafficText.selection.max)
    }

    @Test
    fun `traffic typing 7 after focus absorption replaces initial text completely`() {
        val state = SimLimitState("500", "10.0", false, locale = defaultLocale)
        state.onTrafficFocusChanged(true)
        // Absorb click
        state.onTrafficChange(TextFieldValue("10.0", selection = TextRange(2)), defaultLocale)

        // User types '7' replacing selection
        state.onTrafficChange(TextFieldValue("7", selection = TextRange(1)), defaultLocale)

        assertEquals("7", state.trafficText.text)
    }

    @Test
    fun `traffic editing in middle preserves cursor position when unchanged by normalization`() {
        val state = SimLimitState("0", "12.5", false, locale = defaultLocale)
        state.onTrafficChange(TextFieldValue("13.5", selection = TextRange(1)), defaultLocale)
        assertEquals("13.5", state.trafficText.text)
        assertEquals(1, state.trafficText.selection.start)
    }

    @Test
    fun `traffic comma normalization moves cursor to end of normalized text`() {
        val state = SimLimitState("0", "0.0", false, locale = defaultLocale)
        state.onTrafficChange(TextFieldValue("1,5", selection = TextRange(3)), Locale.GERMANY)
        assertEquals("1.5", state.trafficText.text)
        assertEquals(state.trafficText.text.length, state.trafficText.selection.start)
    }

    @Test
    fun `traffic selection covers full text for localized zero value comma`() {
        val state = SimLimitState("0", "0,0", false, locale = defaultLocale)
        assertEquals(0, state.trafficText.selection.start)
        assertEquals(3, state.trafficText.selection.end)
    }

    @Test
    fun `traffic selection is zero range for non-zero value`() {
        val state = SimLimitState("0", "1.5", false, locale = defaultLocale)
        assertEquals(TextRange.Zero, state.trafficText.selection)
    }

    @Test
    fun `traffic selection covers full text for standard zero value`() {
        val state = SimLimitState("0", "0.0", false, locale = defaultLocale)
        assertEquals(0, state.trafficText.selection.start)
        assertEquals(3, state.trafficText.selection.end)
    }

    @Test
    fun `isTrafficUnchanged returns true when traffic text is unchanged`() {
        val state =
            SimLimitState(
                "100",
                "5.0",
                initialTrafficMb = 5120,
                initialRolloverEnabled = false,
                locale = defaultLocale,
            )
        assertTrue(state.isTrafficUnchanged(defaultLocale))
    }

    @Test
    fun `isTrafficUnchanged returns false when traffic text is changed`() {
        val state =
            SimLimitState(
                "100",
                "5.0",
                initialTrafficMb = 5120,
                initialRolloverEnabled = false,
                locale = defaultLocale,
            )
        state.trafficText = TextFieldValue("6.0")
        assertFalse(state.isTrafficUnchanged(defaultLocale))
    }

    @Test
    fun `isTrafficUnchanged returns true when traffic text is numerically equal to initial`() {
        val state =
            SimLimitState(
                "100",
                "5.0",
                initialTrafficMb = 5120,
                initialRolloverEnabled = false,
                locale = defaultLocale,
            )
        state.trafficText = TextFieldValue("5.0")
        assertTrue(state.isTrafficUnchanged(defaultLocale))
    }
}
