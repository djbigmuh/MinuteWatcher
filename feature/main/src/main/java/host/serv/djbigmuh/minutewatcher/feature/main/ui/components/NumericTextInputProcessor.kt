package host.serv.djbigmuh.minutewatcher.feature.main.ui.components

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import host.serv.djbigmuh.minutewatcher.core.common.util.NumberInputNormalizer
import java.util.Locale

internal enum class NumericInputMode {
    MINUTES,
    TRAFFIC,
}

internal object NumericTextInputProcessor {
    /**
     * Processes numeric text input updates.
     *
     * @param locale The explicit locale used for decimal separator and number normalization.
     *               Must not be default implicit locale to ensure deterministic behavior.
     */
    fun process(
        old: TextFieldValue,
        newValue: TextFieldValue,
        maxLength: Int,
        mode: NumericInputMode,
        locale: Locale,
        isPristineInitial: Boolean = false,
    ): TextFieldValue? {
        if (newValue.text.length > maxLength) return null

        val processed = replaceInitialZeroIfNeeded(old, newValue, mode, locale, isPristineInitial)
        val cleanedText = removeLeadingZeros(processed.text)
        val selection = calculateSelection(processed, cleanedText)

        return processed.copy(text = cleanedText, selection = selection)
    }

    private fun replaceInitialZeroIfNeeded(
        old: TextFieldValue,
        newValue: TextFieldValue,
        mode: NumericInputMode,
        locale: Locale,
        isPristineInitial: Boolean,
    ): TextFieldValue {
        val oldText = old.text
        val isZero = NumberInputNormalizer.isZeroValue(oldText)
        val hasDecimal = NumberInputNormalizer.normalize(oldText, locale).contains('.')
        val shouldReplace =
            when (mode) {
                NumericInputMode.MINUTES ->
                    oldText == "0" &&
                        newValue.text.length == 2 &&
                        newValue.text.startsWith("0")

                NumericInputMode.TRAFFIC ->
                    isZero &&
                        newValue.text.length == oldText.length + 1 &&
                        newValue.text.startsWith(oldText) &&
                        (!hasDecimal || isPristineInitial)
            }

        if (!shouldReplace) return newValue

        val typedChar = newValue.text.last()
        return when (mode) {
            NumericInputMode.MINUTES -> handleMinutesZeroReplacement(newValue, typedChar)
            NumericInputMode.TRAFFIC -> handleTrafficZeroReplacement(newValue, typedChar)
        }
    }

    private fun handleMinutesZeroReplacement(
        newValue: TextFieldValue,
        typedChar: Char,
    ): TextFieldValue =
        when (typedChar) {
            in '1'..'9' -> {
                val newText = typedChar.toString()
                newValue.copy(text = newText, selection = TextRange(newText.length))
            }

            '0' -> {
                newValue.copy(text = "0", selection = TextRange(1))
            }

            else -> newValue
        }

    private fun handleTrafficZeroReplacement(
        newValue: TextFieldValue,
        typedChar: Char,
    ): TextFieldValue =
        if (typedChar.isDigit()) {
            val newText = typedChar.toString()
            newValue.copy(text = newText, selection = TextRange(newText.length))
        } else {
            newValue
        }

    private fun removeLeadingZeros(input: String): String {
        if (input.length <= 1) return input
        var startIndex = 0
        while (startIndex < input.length - 1 &&
            input[startIndex] == '0' &&
            input[startIndex + 1].isDigit()
        ) {
            startIndex++
        }
        return input.substring(startIndex)
    }

    private fun calculateSelection(
        processed: TextFieldValue,
        cleanedText: String,
    ): TextRange =
        if (cleanedText == processed.text) {
            TextRange(
                processed.selection.min.coerceIn(0, cleanedText.length),
                processed.selection.max.coerceIn(0, cleanedText.length),
            )
        } else {
            TextRange(cleanedText.length)
        }
}
