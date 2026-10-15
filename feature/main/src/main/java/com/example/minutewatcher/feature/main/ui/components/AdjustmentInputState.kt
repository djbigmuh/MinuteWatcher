package com.example.minutewatcher.feature.main.ui.components

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.example.minutewatcher.core.common.util.NumberInputNormalizer

@Stable
internal class AdjustmentInputState(
    initialText: String,
    val isMinutes: Boolean = true,
) {
    var text by
        mutableStateOf(
            TextFieldValue(
                text = initialText,
                selection =
                    if (NumberInputNormalizer.isZeroValue(initialText)) {
                        TextRange(0, initialText.length)
                    } else {
                        TextRange.Zero
                    },
            ),
        )
    var errorText by mutableStateOf<String?>(null)
    var selectAllPending = false

    fun selectAll() {
        text = text.copy(selection = TextRange(0, text.text.length))
    }

    fun onFocusChanged(isFocused: Boolean) {
        if (isFocused) {
            selectAllPending = true
            selectAll()
        } else {
            selectAllPending = false
        }
    }

    fun onTextChange(newValue: TextFieldValue) {
        if (selectAllPending) {
            selectAllPending = false
            if (newValue.text == text.text) {
                selectAll()
                return
            }
        }

        val maxChars = if (isMinutes) 6 else 10
        if (newValue.text.length > maxChars) return

        val processed = processInput(text, newValue)
        val textStr = removeLeadingZeros(processed.text)

        val selection =
            if (textStr == processed.text) {
                TextRange(
                    processed.selection.min.coerceIn(0, textStr.length),
                    processed.selection.max.coerceIn(0, textStr.length),
                )
            } else {
                TextRange(textStr.length)
            }

        text = processed.copy(text = textStr, selection = selection)
        errorText = null
    }

    private fun processInput(
        old: TextFieldValue,
        newValue: TextFieldValue,
    ): TextFieldValue {
        if (NumberInputNormalizer.isZeroValue(old.text) && newValue.text.length == 2 && newValue.text.startsWith("0")) {
            val typedChar = newValue.text.last()
            if (isMinutes) {
                if (typedChar in '1'..'9') {
                    val newText = typedChar.toString()
                    return newValue.copy(text = newText, selection = TextRange(newText.length))
                } else if (typedChar == '0') {
                    return newValue.copy(text = "0", selection = TextRange(1))
                }
            } else {
                if (typedChar.isDigit()) {
                    val newText = typedChar.toString()
                    return newValue.copy(text = newText, selection = TextRange(newText.length))
                }
            }
        }
        return newValue
    }

    private fun removeLeadingZeros(input: String): String {
        var textStr = input
        if (textStr.isNotEmpty()) {
            while (textStr.length > 1 && textStr[0] == '0' && Character.isDigit(textStr[1])) {
                textStr = textStr.drop(1)
            }
        }
        return textStr
    }

    companion object {
        val Saver: Saver<AdjustmentInputState, *> =
            listSaver(
                save = {
                    listOf(
                        it.text.text,
                        it.errorText.orEmpty(),
                        it.isMinutes,
                    )
                },
                restore = {
                    AdjustmentInputState(
                        initialText = it[0] as String,
                        isMinutes = it[2] as Boolean,
                    ).apply {
                        errorText = (it[1] as String).takeIf { str -> str.isNotEmpty() }
                    }
                },
            )
    }
}
