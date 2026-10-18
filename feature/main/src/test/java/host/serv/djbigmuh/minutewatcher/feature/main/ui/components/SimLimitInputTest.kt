package host.serv.djbigmuh.minutewatcher.feature.main.ui.components

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
        state.onMinutesChange(TextFieldValue("05", selection = TextRange(2)))
        assertEquals("5", state.minutesText.text)
    }

    @Test
    fun `minutes - old 0, types 0 - results in 0`() {
        val state = SimLimitState("0", "0.0", false, locale = defaultLocale)
        state.onMinutesChange(TextFieldValue("00", selection = TextRange(2)))
        assertEquals("0", state.minutesText.text)
    }

    @Test
    fun `minutes - old 0, full selection, types 7 - results in 7`() {
        val state = SimLimitState("0", "0.0", false, locale = defaultLocale)
        state.selectAllMinutes()
        state.onMinutesChange(TextFieldValue("7", selection = TextRange(1)))
        assertEquals("7", state.minutesText.text)
    }

    @Test
    fun `minutes - old 12, full selection, types 5 - results in 5`() {
        val state = SimLimitState("12", "0.0", false, locale = defaultLocale)
        state.selectAllMinutes()
        state.onMinutesChange(TextFieldValue("5", selection = TextRange(1)))
        assertEquals("5", state.minutesText.text)
    }

    @Test
    fun `minutes - old 0, paste 100 - results in 100`() {
        val state = SimLimitState("0", "0.0", false, locale = defaultLocale)
        state.onMinutesChange(TextFieldValue("0100", selection = TextRange(4)))
        assertEquals("100", state.minutesText.text)
    }

    @Test
    fun `minutes - old 500, full selection, paste 100 - results in 100`() {
        val state = SimLimitState("500", "0.0", false, locale = defaultLocale)
        state.selectAllMinutes()
        state.onMinutesChange(TextFieldValue("100", selection = TextRange(3)))
        assertEquals("100", state.minutesText.text)
    }

    @Test
    fun `traffic - old 0_0, types 5 - results in 5`() {
        val state = SimLimitState("0", "0.0", false, locale = defaultLocale)
        state.onTrafficChange(TextFieldValue("0.05", selection = TextRange(4)))
        assertEquals("5", state.trafficText.text)
    }

    @Test
    fun `traffic - old 0_0, full selection, paste 15_5 - results in 15_5`() {
        val state = SimLimitState("0", "0.0", false, locale = defaultLocale)
        state.selectAllTraffic()
        state.onTrafficChange(TextFieldValue("15.5", selection = TextRange(4)))
        assertEquals("15.5", state.trafficText.text)
    }

    @Test
    fun `traffic - old 5, full selection, paste 500 - results in 500`() {
        val state = SimLimitState("0", "5", false, locale = defaultLocale)
        state.selectAllTraffic()
        state.onTrafficChange(TextFieldValue("500", selection = TextRange(3)))
        assertEquals("500", state.trafficText.text)
    }

    @Test
    fun `traffic - old 0, paste 10_5 - results in 10_5`() {
        val state = SimLimitState("0", "0", false, locale = defaultLocale)
        state.onTrafficChange(TextFieldValue("10.5", selection = TextRange(5)))
        assertEquals("10.5", state.trafficText.text)
    }

    @Test
    fun `limit exceeded - ignores input`() {
        val state = SimLimitState("0", "0", false, locale = defaultLocale)
        state.onMinutesChange(TextFieldValue("1234567", selection = TextRange(7)))
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

        state.onMinutesChange(TextFieldValue("500", selection = TextRange(1)))

        assertFalse(state.minutesSelectAllPending)
        assertEquals(0, state.minutesText.selection.min)
        assertEquals(3, state.minutesText.selection.max)
    }

    @Test
    fun `minutes typing 7 after focus absorption replaces initial text completely`() {
        val state = SimLimitState("500", "10.0", false, locale = defaultLocale)
        state.onMinutesFocusChanged(true)
        state.onMinutesChange(TextFieldValue("500", selection = TextRange(1)))

        state.onMinutesChange(TextFieldValue("7", selection = TextRange(1)))

        assertEquals("7", state.minutesText.text)
    }

    @Test
    fun `minutes text change while pending flag set processes input and resets flag`() {
        val state = SimLimitState("500", "10.0", false, locale = defaultLocale)
        state.onMinutesFocusChanged(true)

        state.onMinutesChange(TextFieldValue("700", selection = TextRange(3)))

        assertFalse(state.minutesSelectAllPending)
        assertEquals("700", state.minutesText.text)
    }

    @Test
    fun `minutes selection only event after flag cleared respects requested cursor position`() {
        val state = SimLimitState("500", "10.0", false, locale = defaultLocale)
        state.onMinutesFocusChanged(true)
        state.onMinutesChange(TextFieldValue("500", selection = TextRange(1)))

        state.onMinutesChange(TextFieldValue("500", selection = TextRange(2)))

        assertEquals(2, state.minutesText.selection.start)
        assertEquals(2, state.minutesText.selection.end)
    }

    @Test
    fun `minutes insertion in middle preserves cursor position when unchanged by normalization`() {
        val state = SimLimitState("1234", "0.0", false, locale = defaultLocale)
        state.onMinutesChange(TextFieldValue("12934", selection = TextRange(3)))
        assertEquals("12934", state.minutesText.text)
        assertEquals(3, state.minutesText.selection.start)
        assertEquals(3, state.minutesText.selection.end)
    }

    @Test
    fun `minutes deletion in middle preserves cursor position when unchanged by normalization`() {
        val state = SimLimitState("1234", "0.0", false, locale = defaultLocale)
        state.onMinutesChange(TextFieldValue("134", selection = TextRange(1)))
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

        state.onTrafficChange(TextFieldValue("10.0", selection = TextRange(2)))

        assertFalse(state.trafficSelectAllPending)
        assertEquals(0, state.trafficText.selection.min)
        assertEquals(4, state.trafficText.selection.max)
    }

    @Test
    fun `traffic typing 7 after focus absorption replaces initial text completely`() {
        val state = SimLimitState("500", "10.0", false, locale = defaultLocale)
        state.onTrafficFocusChanged(true)
        state.onTrafficChange(TextFieldValue("10.0", selection = TextRange(2)))

        state.onTrafficChange(TextFieldValue("7", selection = TextRange(1)))

        assertEquals("7", state.trafficText.text)
    }

    @Test
    fun `traffic editing in middle preserves cursor position when unchanged by normalization`() {
        val state = SimLimitState("0", "12.5", false, locale = defaultLocale)
        state.onTrafficChange(TextFieldValue("13.5", selection = TextRange(1)))
        assertEquals("13.5", state.trafficText.text)
        assertEquals(1, state.trafficText.selection.start)
    }

    @Test
    fun `traffic field preserves comma separator as typed and validates correctly for germany locale`() {
        val state = SimLimitState("0", "0,0", false, locale = Locale.GERMANY)
        state.onTrafficChange(TextFieldValue("1,5", selection = TextRange(3)))
        assertEquals("1,5", state.trafficText.text)
        assertEquals(3, state.trafficText.selection.start)

        val result =
            SimLimitValidator.validate(
                "100",
                state.trafficText.text,
                "req",
                "inv",
                "min",
                "traf",
                Locale.GERMANY,
            )
        assertTrue(result is SimLimitValidationResult.Valid)
        assertEquals(1.5f, (result as SimLimitValidationResult.Valid).trafficGb)
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

    @Test
    fun `stepwise traffic input with comma preserves text and validates correctly`() {
        val state = SimLimitState("100", "0,0", false, locale = Locale.GERMANY)
        state.onTrafficChange(TextFieldValue("1", selection = TextRange(1)))
        assertEquals("1", state.trafficText.text)

        state.onTrafficChange(TextFieldValue("1,", selection = TextRange(2)))
        assertEquals("1,", state.trafficText.text)

        state.onTrafficChange(TextFieldValue("1,5", selection = TextRange(3)))
        assertEquals("1,5", state.trafficText.text)

        val result =
            SimLimitValidator.validate(
                "100",
                state.trafficText.text,
                "req",
                "inv",
                "min",
                "traf",
                Locale.GERMANY,
            )
        assertTrue(result is SimLimitValidationResult.Valid)
        assertEquals(1.5f, (result as SimLimitValidationResult.Valid).trafficGb)
    }

    @Test
    fun `full traffic text replacement with comma parses successfully`() {
        val state = SimLimitState("100", "5,0", false, locale = Locale.GERMANY)
        state.selectAllTraffic()
        state.onTrafficChange(TextFieldValue("10,5", selection = TextRange(4)))
        assertEquals("10,5", state.trafficText.text)

        val result =
            SimLimitValidator.validate(
                "100",
                state.trafficText.text,
                "req",
                "inv",
                "min",
                "traf",
                Locale.GERMANY,
            )
        assertTrue(result is SimLimitValidationResult.Valid)
        assertEquals(10.5f, (result as SimLimitValidationResult.Valid).trafficGb)
    }

    @Test
    fun `fractional minutes with comma validation fails with invalid number`() {
        val state = SimLimitState("10,5", "5,0", false, locale = Locale.GERMANY)
        val result =
            SimLimitValidator.validate(
                state.minutesText.text,
                state.trafficText.text,
                "req",
                "invalidNumberMsg",
                "min",
                "traf",
                Locale.GERMANY,
            )
        assertTrue(result is SimLimitValidationResult.Invalid)
        val inv = result as SimLimitValidationResult.Invalid
        assertEquals("invalidNumberMsg", inv.minutesError)
        assertEquals(null, inv.trafficError)
    }

    @Test
    fun `leading zeros handling across minutes and traffic`() {
        val stateMinutes = SimLimitState("0", "0", false, locale = Locale.GERMANY)
        stateMinutes.onMinutesChange(TextFieldValue("0100", selection = TextRange(4)))
        assertEquals("100", stateMinutes.minutesText.text)

        val stateTraffic1 = SimLimitState("0", "0", false, locale = Locale.GERMANY)
        stateTraffic1.onTrafficChange(TextFieldValue("010.5", selection = TextRange(5)))
        assertEquals("10.5", stateTraffic1.trafficText.text)

        val stateTraffic2 = SimLimitState("0", "0,0", false, locale = Locale.GERMANY)
        stateTraffic2.onTrafficChange(TextFieldValue("00,5", selection = TextRange(4)))
        assertEquals("0,5", stateTraffic2.trafficText.text)

        val result =
            SimLimitValidator.validate(
                "100",
                stateTraffic2.trafficText.text,
                "req",
                "inv",
                "min",
                "traf",
                Locale.GERMANY,
            )
        assertTrue(result is SimLimitValidationResult.Valid)
        assertEquals(0.5f, (result as SimLimitValidationResult.Valid).trafficGb)
    }

    @Test
    fun `us grouping and decimal traffic input preserves text and validates`() {
        val state = SimLimitState("100", "0", false, locale = Locale.US)
        state.onTrafficChange(TextFieldValue("1,234.5", selection = TextRange(7)))
        assertEquals("1,234.5", state.trafficText.text)

        val result =
            SimLimitValidator.validate(
                "100",
                state.trafficText.text,
                "req",
                "inv",
                "min",
                "traf",
                Locale.US,
            )
        assertTrue(result is SimLimitValidationResult.Valid)
        assertEquals(1234.5f, (result as SimLimitValidationResult.Valid).trafficGb)
    }
}
