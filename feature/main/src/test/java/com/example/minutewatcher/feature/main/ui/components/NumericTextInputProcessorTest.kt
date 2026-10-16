package com.example.minutewatcher.feature.main.ui.components

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NumericTextInputProcessorTest {
    @Test
    fun `rejects input exceeding max length`() {
        val old = TextFieldValue("12345", selection = TextRange(5))
        val newValue = TextFieldValue("123456", selection = TextRange(6))
        val result = NumericTextInputProcessor.process(old, newValue, 5, NumericInputMode.MINUTES)
        assertNull(result)
    }

    @Test
    fun `replaces initial zero with digit for minutes`() {
        val old = TextFieldValue("0", selection = TextRange(1))
        val newValue = TextFieldValue("05", selection = TextRange(2))
        val result = NumericTextInputProcessor.process(old, newValue, 6, NumericInputMode.MINUTES)
        assertEquals("5", result?.text)
        assertEquals(TextRange(1), result?.selection)
    }

    @Test
    fun `replaces initial zero with zero for minutes when typed zero`() {
        val old = TextFieldValue("0", selection = TextRange(1))
        val newValue = TextFieldValue("00", selection = TextRange(2))
        val result = NumericTextInputProcessor.process(old, newValue, 6, NumericInputMode.MINUTES)
        assertEquals("0", result?.text)
        assertEquals(TextRange(1), result?.selection)
    }

    @Test
    fun `replaces initial zero with digit for traffic`() {
        val old = TextFieldValue("0", selection = TextRange(1))
        val newValue = TextFieldValue("05", selection = TextRange(2))
        val result = NumericTextInputProcessor.process(old, newValue, 10, NumericInputMode.TRAFFIC)
        assertEquals("5", result?.text)
        assertEquals(TextRange(1), result?.selection)
    }

    @Test
    fun `removes leading zeros while preserving decimal and single zero`() {
        val old = TextFieldValue("0", selection = TextRange(1))
        val newValue = TextFieldValue("005", selection = TextRange(3))
        val result = NumericTextInputProcessor.process(old, newValue, 6, NumericInputMode.MINUTES)
        assertEquals("5", result?.text)

        val decimalOld = TextFieldValue("0", selection = TextRange(1))
        val decimalNew = TextFieldValue("0.5", selection = TextRange(3))
        val decimalResult = NumericTextInputProcessor.process(decimalOld, decimalNew, 10, NumericInputMode.TRAFFIC)
        assertEquals("0.5", decimalResult?.text)
    }

    @Test
    fun `clamps selection or moves cursor to end on text change`() {
        val old = TextFieldValue("123", selection = TextRange(3))
        val newValue = TextFieldValue("1234", selection = TextRange(4))
        val result = NumericTextInputProcessor.process(old, newValue, 6, NumericInputMode.MINUTES)
        assertEquals("1234", result?.text)
        assertEquals(TextRange(4), result?.selection)
    }
}
