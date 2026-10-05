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
import java.util.Locale

@Stable
internal class AdjustmentInputState(
    initialText: String,
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

    fun onTextChange(
        newValue: TextFieldValue,
        locale: Locale,
    ) {
        if (selectAllPending) {
            selectAllPending = false
            if (newValue.text == text.text) {
                selectAll()
                return
            }
        }

        val processed = newValue
        val normalized = NumberInputNormalizer.normalize(processed.text, locale)

        val selection =
            if (normalized == processed.text) {
                TextRange(
                    processed.selection.min.coerceIn(0, normalized.length),
                    processed.selection.max.coerceIn(0, normalized.length),
                )
            } else {
                TextRange(normalized.length)
            }

        text = processed.copy(text = normalized, selection = selection)
        errorText = null
    }

    companion object {
        val Saver: Saver<AdjustmentInputState, *> =
            listSaver(
                save = {
                    listOf(
                        it.text.text,
                        it.errorText.orEmpty(),
                    )
                },
                restore = {
                    AdjustmentInputState(
                        initialText = it[0],
                    ).apply {
                        errorText = it[1].takeIf { str -> str.isNotEmpty() }
                    }
                },
            )
    }
}
