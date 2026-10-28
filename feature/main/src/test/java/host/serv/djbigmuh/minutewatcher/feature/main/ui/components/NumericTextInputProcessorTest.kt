package host.serv.djbigmuh.minutewatcher.feature.main.ui.components

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class NumericTextInputProcessorTest {
    @Test
    fun `rejects input exceeding max length`() {
        val old = TextFieldValue("12345", selection = TextRange(5))
        val newValue = TextFieldValue("123456", selection = TextRange(6))
        val result = NumericTextInputProcessor.process(old, newValue, 5, NumericInputMode.MINUTES, Locale.US)
        assertNull(result)
    }

    @Test
    fun `replaces initial zero with digit for minutes`() {
        val old = TextFieldValue("0", selection = TextRange(1))
        val newValue = TextFieldValue("05", selection = TextRange(2))
        val result = NumericTextInputProcessor.process(old, newValue, 6, NumericInputMode.MINUTES, Locale.US)
        assertEquals("5", result?.text)
        assertEquals(TextRange(1), result?.selection as Any?)
    }

    @Test
    fun `replaces initial zero with zero for minutes when typed zero`() {
        val old = TextFieldValue("0", selection = TextRange(1))
        val newValue = TextFieldValue("00", selection = TextRange(2))
        val result = NumericTextInputProcessor.process(old, newValue, 6, NumericInputMode.MINUTES, Locale.US)
        assertEquals("0", result?.text)
        assertEquals(TextRange(1), result?.selection as Any?)
    }

    @Test
    fun `replaces initial zero with digit for traffic`() {
        val old = TextFieldValue("0", selection = TextRange(1))
        val newValue = TextFieldValue("05", selection = TextRange(2))
        val result = NumericTextInputProcessor.process(old, newValue, 10, NumericInputMode.TRAFFIC, Locale.US)
        assertEquals("5", result?.text)
        assertEquals(TextRange(1), result?.selection as Any?)
    }

    @Test
    fun `removes leading zeros while preserving decimal and single zero`() {
        val old = TextFieldValue("0", selection = TextRange(1))
        val newValue = TextFieldValue("005", selection = TextRange(3))
        val result = NumericTextInputProcessor.process(old, newValue, 6, NumericInputMode.MINUTES, Locale.US)
        assertEquals("5", result?.text)

        val decimalOld = TextFieldValue("0", selection = TextRange(1))
        val decimalNew = TextFieldValue("0.5", selection = TextRange(3))
        val decimalResult =
            NumericTextInputProcessor.process(
                decimalOld,
                decimalNew,
                10,
                NumericInputMode.TRAFFIC,
                Locale.US,
            )
        assertEquals("0.5", decimalResult?.text)
    }

    @Test
    fun `clamps selection or moves cursor to end on text change`() {
        val old = TextFieldValue("123", selection = TextRange(3))
        val newValue = TextFieldValue("1234", selection = TextRange(4))
        val result = NumericTextInputProcessor.process(old, newValue, 6, NumericInputMode.MINUTES, Locale.US)
        assertEquals("1234", result?.text)
        assertEquals(TextRange(4), result?.selection as Any?)
    }

    @Test
    fun `traffic pristine initial placeholder 0 0 appended 5 gives 5`() {
        val old = TextFieldValue("0.0")
        val newValue = TextFieldValue("0.05")
        val result =
            NumericTextInputProcessor.process(
                old,
                newValue,
                10,
                NumericInputMode.TRAFFIC,
                Locale.US,
                true,
            )
        assertEquals("5", result?.text)
    }

    @Test
    fun `traffic user zero appended 5 gives 5`() {
        val old = TextFieldValue("0")
        val newValue = TextFieldValue("05")
        val result =
            NumericTextInputProcessor.process(
                old,
                newValue,
                10,
                NumericInputMode.TRAFFIC,
                Locale.US,
                false,
            )
        assertEquals("5", result?.text)
    }

    @Test
    fun `traffic double zero appended 5 gives 5`() {
        val old = TextFieldValue("00")
        val newValue = TextFieldValue("005")
        val result =
            NumericTextInputProcessor.process(
                old,
                newValue,
                10,
                NumericInputMode.TRAFFIC,
                Locale.US,
                false,
            )
        assertEquals("5", result?.text)
    }

    @Test
    fun `traffic decimal point old appended 0 gives 0 dot 0`() {
        val old = TextFieldValue("0.")
        val newValue = TextFieldValue("0.0")
        val result =
            NumericTextInputProcessor.process(
                old,
                newValue,
                10,
                NumericInputMode.TRAFFIC,
                Locale.US,
                false,
            )
        assertEquals("0.0", result?.text)
    }

    @Test
    fun `traffic edited decimal old appended 5 appends correctly`() {
        val old = TextFieldValue("0.0")
        val newValue = TextFieldValue("0.05")
        val result =
            NumericTextInputProcessor.process(
                old,
                newValue,
                10,
                NumericInputMode.TRAFFIC,
                Locale.US,
                false,
            )
        assertEquals("0.05", result?.text)
    }

    @Test
    fun `traffic comma locale edited decimal old appended 5 appends correctly`() {
        val locale = Locale.GERMANY
        val old = TextFieldValue("0,0")
        val newValue = TextFieldValue("0,05")
        val result =
            NumericTextInputProcessor.process(
                old,
                newValue,
                10,
                NumericInputMode.TRAFFIC,
                locale,
                false,
            )
        assertEquals("0,05", result?.text)
    }

    @Test
    fun `traffic zero plus zero stays zero`() {
        val old = TextFieldValue("0")
        val newValue = TextFieldValue("00")
        val result =
            NumericTextInputProcessor.process(
                old,
                newValue,
                10,
                NumericInputMode.TRAFFIC,
                Locale.US,
                false,
            )
        assertEquals("0", result?.text)
    }

    @Test
    fun `minutes zero plus five gives five and zero plus zero stays zero`() {
        val old = TextFieldValue("0")
        val newValue1 = TextFieldValue("05")
        val r1 =
            NumericTextInputProcessor.process(
                old,
                newValue1,
                6,
                NumericInputMode.MINUTES,
                Locale.US,
            )
        assertEquals("5", r1?.text)

        val newValue2 = TextFieldValue("00")
        val r2 =
            NumericTextInputProcessor.process(
                old,
                newValue2,
                6,
                NumericInputMode.MINUTES,
                Locale.US,
            )
        assertEquals("0", r2?.text)
    }

    @Test
    fun `sequential input 0 dot 0 5 in traffic results in 0 dot 05`() {
        val current = TextFieldValue("0.0")
        val next = TextFieldValue("0.05")
        val res =
            NumericTextInputProcessor.process(
                current,
                next,
                10,
                NumericInputMode.TRAFFIC,
                Locale.US,
                false,
            )
        assertEquals("0.05", res?.text)
    }

    @Test
    fun `removeLeadingZeros processes edge cases correctly`() {
        val testCases =
            mapOf(
                "0" to "0",
                "00" to "0",
                "000" to "0",
                "007" to "7",
                "0.5" to "0.5",
                "00.5" to "0.5",
                "10" to "10",
                "" to "",
            )
        for ((input, expected) in testCases) {
            val old = TextFieldValue("")
            val newValue = TextFieldValue(input)
            val result =
                NumericTextInputProcessor.process(
                    old,
                    newValue,
                    20,
                    NumericInputMode.TRAFFIC,
                    Locale.US,
                    true,
                )
            assertEquals("Failed for input '$input'", expected, result?.text)
        }
    }

    @Test
    fun `comma decimal separator locale processes identically to dot`() {
        val old = TextFieldValue("0,0")
        val newValue = TextFieldValue("0,05")
        val result =
            NumericTextInputProcessor.process(
                old,
                newValue,
                10,
                NumericInputMode.TRAFFIC,
                Locale.GERMANY,
                false,
            )
        assertEquals("0,05", result?.text)
    }

    @Test
    fun `process function locale parameter has no default value`() {
        val method =
            NumericTextInputProcessor::class
                .members
                .first { it.name == "process" }
        val localeParam = method.parameters.first { it.name == "locale" }
        assertFalse("locale parameter must not have default value", localeParam.isOptional)
    }
}
