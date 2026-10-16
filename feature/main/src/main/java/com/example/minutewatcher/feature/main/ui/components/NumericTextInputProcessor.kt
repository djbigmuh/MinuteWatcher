package com.example.minutewatcher.feature.main.ui.components

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.example.minutewatcher.core.common.util.NumberInputNormalizer

internal enum class NumericInputMode {
    MINUTES,
    TRAFFIC,
}

internal object NumericTextInputProcessor {
    fun process(
        old: TextFieldValue,
        newValue: TextFieldValue,
        maxLength: Int,
        mode: NumericInputMode,
    ): TextFieldValue? {
        if (newValue.text.length > maxLength) return null

        val processed = replaceInitialZeroIfNeeded(old, newValue, mode)
        val cleanedText = removeLeadingZeros(processed.text)
        val selection = calculateSelection(processed, cleanedText)

        return processed.copy(text = cleanedText, selection = selection)
    }

    private fun replaceInitialZeroIfNeeded(
        old: TextFieldValue,
        newValue: TextFieldValue,
        mode: NumericInputMode,
    ): TextFieldValue {
        val oldText = old.text
        val isZero = NumberInputNormalizer.isZeroValue(oldText)
        val shouldReplace =
            when (mode) {
                NumericInputMode.MINUTES ->
                    oldText == "0" &&
                        newValue.text.length == 2 &&
                        newValue.text.startsWith("0")

                NumericInputMode.TRAFFIC ->
                    isZero &&
                        newValue.text.length == oldText.length + 1 &&
                        newValue.text.startsWith(oldText)
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
        when {
            typedChar in '1'..'9' -> {
                val newText = typedChar.toString()
                newValue.copy(text = newText, selection = TextRange(newText.length))
            }

            typedChar == '0' -> {
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
        var textStr = input
        if (textStr.isNotEmpty()) {
            while (textStr.length > 1 &&
                textStr[0] == '0' &&
                Character.isDigit(textStr[1])
            ) {
                textStr = textStr.drop(1)
            }
        }
        return textStr
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
