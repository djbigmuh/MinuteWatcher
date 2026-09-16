package com.example.minutewatcher.feature.main.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.example.minutewatcher.core.common.util.NumberInputNormalizer
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.resources.R
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SimLimitComposeDialog(
    slot: Int,
    operatorName: String,
    initialMinutes: Int,
    initialTrafficMb: Int,
    initialRolloverEnabled: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (minutes: Int, trafficMb: Int, rollover: Boolean) -> Unit,
    onReset: () -> Unit,
) {
    val state = rememberSimLimitState(slot, initialMinutes, initialTrafficMb, initialRolloverEnabled)
    var showResetConfirm by remember { mutableStateOf(false) }

    if (showResetConfirm) {
        ResetConfirmationDialog(
            onConfirm = {
                onReset()
                showResetConfirm = false
            },
            onDismiss = { showResetConfirm = false },
        )
    }

    val validationMessages = rememberValidationMessages()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.limits_for_operator, operatorName)) },
        text = { DialogFields(state = state) },
        confirmButton = {
            DialogButtons(
                onConfirm = {
                    when (val result = state.validate(validationMessages)) {
                        is SimLimitValidationResult.Valid -> {
                            onConfirm(
                                result.minutes,
                                (result.trafficGb * Constants.MB_PER_GB).roundToInt(),
                                state.rolloverEnabled,
                            )
                        }

                        is SimLimitValidationResult.Invalid -> {
                            // Ошибки уже установлены в state внутри validate()
                        }
                    }
                },
                onReset = { showResetConfirm = true },
                onDismiss = onDismiss,
            )
        },
        dismissButton = null,
    )
}

@Composable
private fun rememberValidationMessages(): ValidationMessages =
    ValidationMessages(
        required = stringResource(id = R.string.validation_error_field_required),
        invalidNumber = stringResource(id = R.string.validation_error_invalid_number),
        minutesRange =
            stringResource(
                id = R.string.validation_error_invalid_range,
                "0",
                Constants.MAX_MINUTES_LIMIT.toString(),
            ),
        trafficRange =
            stringResource(
                id = R.string.validation_error_invalid_range,
                "0",
                Constants.MAX_TRAFFIC_LIMIT_GB.toInt().toString(),
            ),
    )

private data class ValidationMessages(
    val required: String,
    val invalidNumber: String,
    val minutesRange: String,
    val trafficRange: String,
)

@Composable
private fun rememberSimLimitState(
    slot: Int,
    initialMinutes: Int,
    initialTrafficMb: Int,
    initialRolloverEnabled: Boolean,
): SimLimitState {
    val initialTrafficText =
        remember(initialTrafficMb) {
            val gb = initialTrafficMb / Constants.MB_PER_GB
            "%.1f".format(Locale.US, gb)
        }

    return key(slot) {
        rememberSaveable(saver = SimLimitState.Saver) {
            SimLimitState(
                initialMinutesText = initialMinutes.toString(),
                initialTrafficText = initialTrafficText,
                initialRolloverEnabled = initialRolloverEnabled,
            )
        }
    }
}

@Stable
private class SimLimitState(
    initialMinutesText: String,
    initialTrafficText: String,
    initialRolloverEnabled: Boolean,
) {
    var minutesText by
        mutableStateOf(
            TextFieldValue(
                text = initialMinutesText,
                selection =
                    if (initialMinutesText == "0") {
                        TextRange(0, initialMinutesText.length)
                    } else {
                        TextRange.Zero
                    },
            ),
        )
    var trafficText by
        mutableStateOf(
            TextFieldValue(
                text = initialTrafficText,
                selection =
                    if (initialTrafficText == "0" || initialTrafficText == "0.0") {
                        TextRange(0, initialTrafficText.length)
                    } else {
                        TextRange.Zero
                    },
            ),
        )
    var rolloverEnabled by mutableStateOf(initialRolloverEnabled)
    var minutesError by mutableStateOf<String?>(null)
    var trafficError by mutableStateOf<String?>(null)

    fun selectAllMinutes() {
        minutesText = minutesText.copy(selection = TextRange(0, minutesText.text.length))
    }

    fun selectAllTraffic() {
        trafficText = trafficText.copy(selection = TextRange(0, trafficText.text.length))
    }

    companion object {
        private const val MAX_MINUTES_CHARS = 6
        private const val MAX_TRAFFIC_CHARS = 10

        val Saver: Saver<SimLimitState, *> =
            listSaver(
                save = {
                    listOf(
                        it.minutesText.text,
                        it.trafficText.text,
                        it.rolloverEnabled,
                        it.minutesError ?: "",
                        it.trafficError ?: "",
                    )
                },
                restore = {
                    SimLimitState(
                        initialMinutesText = it[0] as String,
                        initialTrafficText = it[1] as String,
                        initialRolloverEnabled = it[2] as Boolean,
                    ).apply {
                        minutesError = (it[3] as String).takeIf { it.isNotEmpty() }
                        trafficError = (it[4] as String).takeIf { it.isNotEmpty() }
                    }
                },
            )
    }

    fun validate(msgs: ValidationMessages): SimLimitValidationResult {
        val result =
            SimLimitValidator.validate(
                minutesText.text,
                trafficText.text,
                msgs.required,
                msgs.invalidNumber,
                msgs.minutesRange,
                msgs.trafficRange,
            )
        when (result) {
            is SimLimitValidationResult.Valid -> {
                minutesError = null
                trafficError = null
            }

            is SimLimitValidationResult.Invalid -> {
                minutesError = result.minutesError
                trafficError = result.trafficError
            }
        }
        return result
    }

    fun onMinutesChange(newValue: TextFieldValue) {
        if (newValue.text.length > MAX_MINUTES_CHARS) return

        val old = minutesText
        var processed = newValue

        // If it was "0" and user typed a digit, just replace it regardless of selection
        if (shouldReplaceInitialZero(old, newValue)) {
            val typed = newValue.text.filter { it != '0' }.ifEmpty { "0" }
            if (typed.all { it.isDigit() }) {
                processed = newValue.copy(text = typed, selection = TextRange(typed.length))
            }
        } else if (isFullSelectionOverwrite(old, newValue)) {
            val added = newValue.text.removePrefix(old.text).ifEmpty { newValue.text.removeSuffix(old.text) }
            if (added.isNotEmpty() && added != newValue.text) {
                processed = newValue.copy(text = added, selection = TextRange(added.length))
            }
        }

        val normalized = NumberInputNormalizer.normalize(processed.text)
        minutesText = processed.copy(text = normalized)
        minutesError = null
    }

    private fun shouldReplaceInitialZero(
        old: TextFieldValue,
        newValue: TextFieldValue,
    ): Boolean = old.text == "0" && newValue.text.length == 2 && newValue.text != "0"

    private fun isFullSelectionOverwrite(
        old: TextFieldValue,
        newValue: TextFieldValue,
    ): Boolean =
        old.selection.start == 0 &&
            old.selection.end == old.text.length &&
            old.text.isNotEmpty() &&
            newValue.text.length > old.text.length

    fun onTrafficChange(newValue: TextFieldValue) {
        if (newValue.text.length > MAX_TRAFFIC_CHARS) return

        val old = trafficText
        var processed = newValue

        // If it was "0" or "0.0" and user typed a digit, just replace it
        if (shouldReplaceInitialTrafficZero(old, newValue)) {
            val typedChar = newValue.text.find { it !in old.text }
            if (typedChar != null && typedChar.isDigit()) {
                val newText = typedChar.toString()
                processed = newValue.copy(text = newText, selection = TextRange(newText.length))
            }
        }

        // Standard selection overwrite logic
        if (processed == newValue && isFullSelectionOverwrite(old, newValue)) {
            val added = newValue.text.removePrefix(old.text).ifEmpty { newValue.text.removeSuffix(old.text) }
            if (added.isNotEmpty() && added != newValue.text) {
                processed = newValue.copy(text = added, selection = TextRange(added.length))
            }
        }

        val normalized = NumberInputNormalizer.normalize(processed.text)
        trafficText = processed.copy(text = normalized)
        trafficError = null
    }

    private fun shouldReplaceInitialTrafficZero(
        old: TextFieldValue,
        newValue: TextFieldValue,
    ): Boolean = (old.text == "0" || old.text == "0.0") && newValue.text.length > old.text.length
}

@Composable
private fun DialogFields(state: SimLimitState) {
    val minutesFocusRequester = remember { FocusRequester() }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        minutesFocusRequester.requestFocus()
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = state.minutesText,
            onValueChange = { state.onMinutesChange(it) },
            label = { Text(stringResource(id = R.string.set_minutes_limit)) },
            placeholder = { Text("0") },
            isError = state.minutesError != null,
            supportingText = { state.minutesError?.let { Text(text = it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            suffix = { Text(text = stringResource(id = R.string.unit_minutes)) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusRequester(minutesFocusRequester)
                    .onFocusChanged { if (it.isFocused) state.selectAllMinutes() },
        )
        OutlinedTextField(
            value = state.trafficText,
            onValueChange = { state.onTrafficChange(it) },
            label = { Text(stringResource(id = R.string.set_traffic_limit)) },
            placeholder = { Text("0") },
            isError = state.trafficError != null,
            supportingText = { state.trafficError?.let { Text(text = it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            suffix = { Text(text = stringResource(id = R.string.unit_gigabytes)) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (it.isFocused) state.selectAllTraffic() },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = state.rolloverEnabled, onCheckedChange = { state.rolloverEnabled = it })
            Spacer(Modifier.width(8.dp))
            Text(text = stringResource(id = R.string.rollover_label), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun DialogButtons(
    onConfirm: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(id = R.string.set_button))
        }
        OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(id = R.string.reset_limits_button))
        }
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(id = R.string.cancel_button))
        }
    }
}
