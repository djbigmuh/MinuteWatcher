package com.example.minutewatcher.feature.main.ui.components

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // behavior-specific SDK
class AdjustmentInputStateTest {
    @Test
    fun `focus gained sets pending flag and selects all text`() {
        val state = AdjustmentInputState("380")
        state.onFocusChanged(true)

        assertTrue(state.selectAllPending)
        assertEquals(0, state.text.selection.min)
        assertEquals(3, state.text.selection.max)
    }

    @Test
    fun `selection only event on focus absorbs click and restores full selection`() {
        val state = AdjustmentInputState("380")
        state.onFocusChanged(true)

        // Click-driven selection change
        state.onTextChange(TextFieldValue("380", selection = TextRange(1)))

        assertFalse(state.selectAllPending)
        assertEquals(0, state.text.selection.min)
        assertEquals(3, state.text.selection.max)
    }

    @Test
    fun `typing 7 after focus absorption replaces initial text completely`() {
        val state = AdjustmentInputState("380")
        state.onFocusChanged(true)
        // Absorb click
        state.onTextChange(TextFieldValue("380", selection = TextRange(1)))

        // User types '7'
        state.onTextChange(TextFieldValue("7", selection = TextRange(1)))

        assertEquals("7", state.text.text)
    }

    @Test
    fun `selection only event after flag cleared respects requested cursor position`() {
        val state = AdjustmentInputState("380")
        state.onFocusChanged(true)
        // Absorb click
        state.onTextChange(TextFieldValue("380", selection = TextRange(1)))

        // Second click after flag cleared
        state.onTextChange(TextFieldValue("380", selection = TextRange(2)))

        assertEquals(2, state.text.selection.start)
        assertEquals(2, state.text.selection.end)
    }

    @Test
    fun `editing in middle preserves cursor position when unchanged by normalization`() {
        val state = AdjustmentInputState("1234")
        state.onTextChange(TextFieldValue("12934", selection = TextRange(3)))

        assertEquals("12934", state.text.text)
        assertEquals(3, state.text.selection.start)
        assertEquals(3, state.text.selection.end)
    }
}
