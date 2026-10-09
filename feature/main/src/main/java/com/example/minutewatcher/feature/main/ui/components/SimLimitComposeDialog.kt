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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.example.minutewatcher.core.common.util.FormatUtils
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
    val locale = FormatUtils.getLocale(LocalContext.current)
    val state = rememberSimLimitState(slot, initialMinutes, initialTrafficMb, initialRolloverEnabled, locale)
    var showResetConfirm by remember { mutableStateOf(false) }

    if (showResetConfirm) {
        ResetConfirmationDialog(
            titleRes = R.string.reset_limits_confirmation_title,
            textRes = R.string.reset_limits_confirmation_body,
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
        text = { DialogFields(state = state, locale = locale) },
        confirmButton = {
            DialogButtons(
                onConfirm = {
                    when (val result = state.validate(validationMessages, locale)) {
                        is SimLimitValidationResult.Valid -> {
                            val finalTrafficMb =
                                if (state.isTrafficUnchanged(locale)) {
                                    state.initialTrafficMb
                                } else {
                                    (result.trafficGb * Constants.MB_PER_GB).roundToInt()
                                }
                            onConfirm(
                                result.minutes,
                                finalTrafficMb,
                                state.rolloverEnabled,
                            )
                        }

                        is SimLimitValidationResult.Invalid -> {
                            // Errors are already set in state inside validate()
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
internal fun rememberValidationMessages(): ValidationMessages =
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

internal data class ValidationMessages(
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
    locale: Locale,
): SimLimitState {
    val initialTrafficText =
        remember(initialTrafficMb, locale) {
            val gb = initialTrafficMb / Constants.MB_PER_GB
            // Format with the effective UI locale to display the user's localized decimal separator.
            String.format(locale, "%.1f", gb)
        }

    return key(slot) {
        rememberSaveable(
            inputs = arrayOf<Any?>(initialMinutes, initialTrafficText, initialTrafficMb, initialRolloverEnabled),
            saver = SimLimitState.Saver,
        ) {
            SimLimitState(
                initialMinutesText = initialMinutes.toString(),
                initialTrafficText = initialTrafficText,
                initialRolloverEnabled = initialRolloverEnabled,
                initialTrafficMb = initialTrafficMb,
                locale = locale,
            )
        }
    }
}

@Stable
internal class SimLimitState(
    initialMinutesText: String,
    initialTrafficText: String,
    initialRolloverEnabled: Boolean = false,
    val initialTrafficMb: Int = 0,
    locale: Locale = Locale.getDefault(),
) {
    val initialTrafficNormalized: String = NumberInputNormalizer.normalize(initialTrafficText, locale)

    fun isTrafficUnchanged(locale: Locale): Boolean {
        val currentNormalized = NumberInputNormalizer.normalize(trafficText.text, locale)
        return currentNormalized == initialTrafficNormalized
    }

    var minutesText by
        mutableStateOf(
            TextFieldValue(
                text = initialMinutesText,
                selection =
                    if (NumberInputNormalizer.isZeroValue(initialMinutesText)) {
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
                    if (NumberInputNormalizer.isZeroValue(initialTrafficText)) {
                        TextRange(0, initialTrafficText.length)
                    } else {
                        TextRange.Zero
                    },
            ),
        )
    var rolloverEnabled by mutableStateOf(initialRolloverEnabled)
    var minutesError by mutableStateOf<String?>(null)
    var trafficError by mutableStateOf<String?>(null)

    var minutesSelectAllPending = false
    var trafficSelectAllPending = false

    fun selectAllMinutes() {
        minutesText = minutesText.copy(selection = TextRange(0, minutesText.text.length))
    }

    fun selectAllTraffic() {
        trafficText = trafficText.copy(selection = TextRange(0, trafficText.text.length))
    }

    fun onMinutesFocusChanged(isFocused: Boolean) {
        if (isFocused) {
            minutesSelectAllPending = true
            selectAllMinutes()
        } else {
            minutesSelectAllPending = false
        }
    }

    fun onTrafficFocusChanged(isFocused: Boolean) {
        if (isFocused) {
            trafficSelectAllPending = true
            selectAllTraffic()
        } else {
            trafficSelectAllPending = false
        }
    }

    companion object {
        private const val MAX_MINUTES_CHARS = 6
        private const val MAX_TRAFFIC_CHARS = 10
        private const val INDEX_MINUTES_TEXT = 0
        private const val INDEX_TRAFFIC_TEXT = 1
        private const val INDEX_ROLLOVER_ENABLED = 2
        private const val INDEX_INITIAL_TRAFFIC_MB = 3
        private const val INDEX_MINUTES_ERROR = 4
        private const val INDEX_TRAFFIC_ERROR = 5

        val Saver: Saver<SimLimitState, *> =
            listSaver(
                save = {
                    listOf(
                        it.minutesText.text,
                        it.trafficText.text,
                        it.rolloverEnabled,
                        it.initialTrafficMb,
                        it.minutesError.orEmpty(),
                        it.trafficError.orEmpty(),
                    )
                },
                restore = {
                    SimLimitState(
                        initialMinutesText = it[INDEX_MINUTES_TEXT] as String,
                        initialTrafficText = it[INDEX_TRAFFIC_TEXT] as String,
                        initialRolloverEnabled = it[INDEX_ROLLOVER_ENABLED] as Boolean,
                        initialTrafficMb = it[INDEX_INITIAL_TRAFFIC_MB] as Int,
                        locale = Locale.getDefault(),
                    ).apply {
                        minutesError = (it[INDEX_MINUTES_ERROR] as String).takeIf { str -> str.isNotEmpty() }
                        trafficError = (it[INDEX_TRAFFIC_ERROR] as String).takeIf { str -> str.isNotEmpty() }
                    }
                },
            )
    }

    fun validate(
        msgs: ValidationMessages,
        locale: Locale,
    ): SimLimitValidationResult {
        val result =
            SimLimitValidator.validate(
                minutesText.text,
                trafficText.text,
                msgs.required,
                msgs.invalidNumber,
                msgs.minutesRange,
                msgs.trafficRange,
                locale,
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

    /**
     * Handles changes in the minutes input field.
     *
     * **Contract:**
     * 1. Single-character input: if the current text is "0" and the user types a digit '1'-'9',
     *    the "0" is replaced by that digit. Typing '0' results in "0".
     * 2. Full selection replacement: trusts [newValue] as the result of the replacement.
     * 3. All other cases: normalizes the input using [NumberInputNormalizer] and strips leading zeros.
     */
    fun onMinutesChange(
        newValue: TextFieldValue,
        locale: Locale,
    ) {
        if (minutesSelectAllPending) {
            minutesSelectAllPending = false
            if (newValue.text == minutesText.text) {
                selectAllMinutes()
                return
            }
        }

        if (newValue.text.length > MAX_MINUTES_CHARS) return

        val old = minutesText
        var processed = newValue

        // Replace initial "0" only on single-character addition
        if (shouldReplaceInitialZero(old, newValue)) {
            val typedChar = newValue.text.last()
            if (typedChar in '1'..'9') {
                val newText = typedChar.toString()
                processed = newValue.copy(text = newText, selection = TextRange(newText.length))
            } else if (typedChar == '0') {
                processed = newValue.copy(text = "0", selection = TextRange(1))
            }
        }

        var text = NumberInputNormalizer.normalize(processed.text, locale)
        // Strip leading zeros for minutes
        if (text.length > 1 && text.startsWith("0")) {
            text = text.dropWhile { it == '0' }.ifEmpty { "0" }
        }

        val selection =
            if (text == processed.text) {
                TextRange(
                    processed.selection.min.coerceIn(0, text.length),
                    processed.selection.max.coerceIn(0, text.length),
                )
            } else {
                TextRange(text.length)
            }

        minutesText = processed.copy(text = text, selection = selection)
        minutesError = null
    }

    private fun shouldReplaceInitialZero(
        old: TextFieldValue,
        newValue: TextFieldValue,
    ): Boolean = old.text == "0" && newValue.text.length == 2 && newValue.text.startsWith("0")

    /**
     * Handles changes in the traffic input field.
     *
     * **Contract:**
     * 1. Single-character input: if the current text is "0" or "0.0" and the user types a digit,
     *    the initial text is replaced by that digit.
     * 2. Full selection replacement: trusts [newValue] as the result of the replacement.
     * 3. All other cases: normalizes the input using [NumberInputNormalizer] and strips leading zeros
     *    if not a decimal.
     */
    fun onTrafficChange(
        newValue: TextFieldValue,
        locale: Locale,
    ) {
        if (trafficSelectAllPending) {
            trafficSelectAllPending = false
            if (newValue.text == trafficText.text) {
                selectAllTraffic()
                return
            }
        }

        if (newValue.text.length > MAX_TRAFFIC_CHARS) return

        val old = trafficText
        var processed = newValue

        // Replace initial "0" or "0.0" only on single-character addition
        if (shouldReplaceInitialTrafficZero(old, newValue)) {
            val typedChar = newValue.text.last()
            if (typedChar.isDigit()) {
                val newText = typedChar.toString()
                processed = newValue.copy(text = newText, selection = TextRange(newText.length))
            }
        }

        var text = NumberInputNormalizer.normalize(processed.text, locale)
        // Strip leading zeros for traffic if it's not a decimal starting with "0."
        if (text.length > 1 && text.startsWith("0") && !text.startsWith("0.")) {
            text = text.dropWhile { it == '0' }.ifEmpty { "0" }
        }

        val selection =
            if (text == processed.text) {
                TextRange(
                    processed.selection.min.coerceIn(0, text.length),
                    processed.selection.max.coerceIn(0, text.length),
                )
            } else {
                TextRange(text.length)
            }

        trafficText = processed.copy(text = text, selection = selection)
        trafficError = null
    }

    private fun shouldReplaceInitialTrafficZero(
        old: TextFieldValue,
        newValue: TextFieldValue,
    ): Boolean {
        val isInitial = old.text == "0" || old.text == "0.0"
        return isInitial && newValue.text.length == old.text.length + 1 && newValue.text.startsWith(old.text)
    }
}

@Composable
private fun DialogFields(
    state: SimLimitState,
    locale: Locale,
) {
    val minutesFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        minutesFocusRequester.requestFocus()
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = state.minutesText,
            onValueChange = { state.onMinutesChange(it, locale) },
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
                    .onFocusChanged { state.onMinutesFocusChanged(it.isFocused) },
        )
        OutlinedTextField(
            value = state.trafficText,
            onValueChange = { state.onTrafficChange(it, locale) },
            label = { Text(stringResource(id = R.string.set_traffic_limit)) },
            placeholder = { Text("0") },
            isError = state.trafficError != null,
            supportingText = { state.trafficError?.let { Text(text = it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            suffix = { Text(text = stringResource(id = R.string.unit_gigabytes)) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .onFocusChanged { state.onTrafficFocusChanged(it.isFocused) },
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
