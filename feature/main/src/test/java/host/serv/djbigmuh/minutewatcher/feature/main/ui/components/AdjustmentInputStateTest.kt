package host.serv.djbigmuh.minutewatcher.feature.main.ui.components

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class AdjustmentInputStateTest {
    private val defaultLocale = Locale.US
    private val franceLocale = Locale.FRANCE

    @Test
    fun `focus gained sets pending flag and selects all text`() {
        val state = AdjustmentInputState("380", isMinutes = true)
        state.onFocusChanged(true)

        assertTrue(state.selectAllPending)
        assertEquals(0, state.text.selection.min)
        assertEquals(3, state.text.selection.max)
    }

    @Test
    fun `selection only event on focus absorbs click and restores full selection`() {
        val state = AdjustmentInputState("380", isMinutes = true)
        state.onFocusChanged(true)

        state.onTextChange(TextFieldValue("380", selection = TextRange(1)))

        assertFalse(state.selectAllPending)
        assertEquals(0, state.text.selection.min)
        assertEquals(3, state.text.selection.max)
    }

    @Test
    fun `typing digit after focus absorption replaces initial text completely`() {
        val state = AdjustmentInputState("380", isMinutes = true)
        state.onFocusChanged(true)
        state.onTextChange(TextFieldValue("380", selection = TextRange(1)))

        state.onTextChange(TextFieldValue("7", selection = TextRange(1)))

        assertEquals("7", state.text.text)
    }

    @Test
    fun `leading zeros are removed for minutes`() {
        val state = AdjustmentInputState("0", isMinutes = true)
        state.onTextChange(TextFieldValue("007", selection = TextRange(3)))

        assertEquals("7", state.text.text)
    }

    @Test
    fun `exceeding max length for minutes is ignored`() {
        val state = AdjustmentInputState("123", isMinutes = true)
        state.onTextChange(TextFieldValue("1234567", selection = TextRange(7)))

        assertEquals("123", state.text.text)
    }

    @Test
    fun `exceeding max length for traffic is ignored`() {
        val state = AdjustmentInputState("1.5", isMinutes = false)
        state.onTextChange(TextFieldValue("12345678901", selection = TextRange(11)))

        assertEquals("1.5", state.text.text)
    }

    @Test
    fun `decimal comma is preserved as typed and parsed correctly by validator`() {
        val state = AdjustmentInputState("1,5", isMinutes = false)
        state.onTextChange(TextFieldValue("1,5", selection = TextRange(3)))

        assertEquals("1,5", state.text.text)

        val validationResult =
            AdjustmentValidator.validate(
                text = state.text.text,
                isMinutes = false,
                locale = franceLocale,
                invalidNumberMsg = "Invalid number",
                invalidRangeMsg = "Invalid range",
            )
        assertTrue(validationResult is AdjustmentValidationResult.Valid)
        assertEquals(1.5f, (validationResult as AdjustmentValidationResult.Valid).value)
    }

    @Test
    fun `non numeric text is preserved as typed and validator returns invalid number error`() {
        val state = AdjustmentInputState("abc", isMinutes = true)
        state.onTextChange(TextFieldValue("abc", selection = TextRange(3)))

        assertEquals("abc", state.text.text)

        val validationResult =
            AdjustmentValidator.validate(
                text = state.text.text,
                isMinutes = true,
                locale = defaultLocale,
                invalidNumberMsg = "Invalid number",
                invalidRangeMsg = "Invalid range",
            )
        assertTrue(validationResult is AdjustmentValidationResult.Invalid)
    }

    private val dummySaverScope = SaverScope { true }

    @Test
    fun `saver saves and restores typed text and non empty errorText`() {
        val original = AdjustmentInputState("100", isMinutes = true)
        original.text = TextFieldValue("250", selection = TextRange(3))
        original.errorText = "Invalid input"

        @Suppress("UNCHECKED_CAST")
        val saver = AdjustmentInputState.saver(defaultLocale) as Saver<AdjustmentInputState, Any>
        val saved = with(saver) { dummySaverScope.save(original) }
        assertNotNull(saved)

        val restored = saver.restore(saved!!)
        assertNotNull(restored)
        assertEquals("250", restored!!.text.text)
        assertEquals("Invalid input", restored.errorText)
        assertEquals(true, restored.isMinutes)
    }

    @Test
    fun `saver restores null errorText when saved errorText is empty`() {
        val original = AdjustmentInputState("100", isMinutes = false)
        original.text = TextFieldValue("2.5")
        original.errorText = null

        @Suppress("UNCHECKED_CAST")
        val saver = AdjustmentInputState.saver(defaultLocale) as Saver<AdjustmentInputState, Any>
        val saved = with(saver) { dummySaverScope.save(original) }
        assertNotNull(saved)

        val restored = saver.restore(saved!!)
        assertNotNull(restored)
        assertEquals("2.5", restored!!.text.text)
        assertNull(restored.errorText)
        assertEquals(false, restored.isMinutes)
    }
}
