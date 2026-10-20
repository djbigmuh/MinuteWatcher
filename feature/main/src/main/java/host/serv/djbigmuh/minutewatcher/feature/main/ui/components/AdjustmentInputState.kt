package host.serv.djbigmuh.minutewatcher.feature.main.ui.components

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import host.serv.djbigmuh.minutewatcher.core.common.util.NumberInputNormalizer
import host.serv.djbigmuh.minutewatcher.core.model.Constants

@Stable
internal class AdjustmentInputState(
    initialText: String,
    val isMinutes: Boolean = true,
) {
    var text by mutableStateOf(
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

        val maxChars =
            if (isMinutes) {
                Constants.UI.MAX_MINUTES_INPUT_CHARS
            } else {
                Constants.UI.MAX_TRAFFIC_INPUT_CHARS
            }
        val mode = if (isMinutes) NumericInputMode.MINUTES else NumericInputMode.TRAFFIC

        val processed = NumericTextInputProcessor.process(text, newValue, maxChars, mode) ?: return

        text = processed
        errorText = null
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
