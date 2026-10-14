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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
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
        title = {
            Text(
                text = stringResource(id = R.string.limits_for_operator, operatorName),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = { DialogFields(state = state) },
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

    val saver = remember(locale) { SimLimitState.saver(locale) }
    return key(slot) {
        rememberSaveable(
            inputs = arrayOf<Any?>(initialMinutes, initialTrafficText, initialTrafficMb, initialRolloverEnabled),
            saver = saver,
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

/**
 * State holder for SIM limit configuration.
 * Stores initial and current values separately; restoration preserves user-entered text,
 * while restoration in a different effective locale resets input to initial values.
 */
@Stable
internal class SimLimitState(
    val initialMinutesText: String,
    val initialTrafficText: String,
    val initialRolloverEnabled: Boolean = false,
    val initialTrafficMb: Int = 0,
    locale: Locale,
    currentMinutesText: String = initialMinutesText,
    currentTrafficText: String = initialTrafficText,
    currentRolloverEnabled: Boolean = initialRolloverEnabled,
) {
    val initialTrafficNormalized: String = NumberInputNormalizer.normalize(initialTrafficText, locale)

    fun isTrafficUnchanged(locale: Locale): Boolean {
        val currentNormalized = NumberInputNormalizer.normalize(trafficText.text, locale)
        return currentNormalized == initialTrafficNormalized
    }

    var minutesText by
        mutableStateOf(
            TextFieldValue(
                text = currentMinutesText,
                selection =
                    if (NumberInputNormalizer.isZeroValue(currentMinutesText)) {
                        TextRange(0, currentMinutesText.length)
                    } else {
                        TextRange.Zero
                    },
            ),
        )
    var trafficText by
        mutableStateOf(
            TextFieldValue(
                text = currentTrafficText,
                selection =
                    if (NumberInputNormalizer.isZeroValue(currentTrafficText)) {
                        TextRange(0, currentTrafficText.length)
                    } else {
                        TextRange.Zero
                    },
            ),
        )
    var rolloverEnabled by mutableStateOf(currentRolloverEnabled)
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
        private const val INDEX_INITIAL_MINUTES_TEXT = 0
        private const val INDEX_INITIAL_TRAFFIC_TEXT = 1
        private const val INDEX_INITIAL_ROLLOVER_ENABLED = 2
        private const val INDEX_INITIAL_TRAFFIC_MB = 3
        private const val INDEX_CURRENT_MINUTES_TEXT = 4
        private const val INDEX_CURRENT_TRAFFIC_TEXT = 5
        private const val INDEX_CURRENT_ROLLOVER_ENABLED = 6
        private const val INDEX_MINUTES_ERROR = 7
        private const val INDEX_TRAFFIC_ERROR = 8
        private const val INDEX_LOCALE_TAG = 9

        /**
         * Saver for [SimLimitState].
         * Stores initial and current values separately; preserves entered text upon state restoration,
         * and resets input to initial values if restored in a different effective locale.
         */
        fun saver(locale: Locale): Saver<SimLimitState, Any> =
            listSaver(
                save = {
                    listOf(
                        it.initialMinutesText,
                        it.initialTrafficText,
                        it.initialRolloverEnabled,
                        it.initialTrafficMb,
                        it.minutesText.text,
                        it.trafficText.text,
                        it.rolloverEnabled,
                        it.minutesError.orEmpty(),
                        it.trafficError.orEmpty(),
                        locale.toLanguageTag(),
                    )
                },
                restore = {
                    val savedLocaleTag = it[INDEX_LOCALE_TAG] as? String
                    if (savedLocaleTag == locale.toLanguageTag()) {
                        SimLimitState(
                            initialMinutesText = it[INDEX_INITIAL_MINUTES_TEXT] as String,
                            initialTrafficText = it[INDEX_INITIAL_TRAFFIC_TEXT] as String,
                            initialRolloverEnabled = it[INDEX_INITIAL_ROLLOVER_ENABLED] as Boolean,
                            initialTrafficMb = it[INDEX_INITIAL_TRAFFIC_MB] as Int,
                            locale = locale,
                            currentMinutesText = it[INDEX_CURRENT_MINUTES_TEXT] as String,
                            currentTrafficText = it[INDEX_CURRENT_TRAFFIC_TEXT] as String,
                            currentRolloverEnabled = it[INDEX_CURRENT_ROLLOVER_ENABLED] as Boolean,
                        ).apply {
                            minutesError = (it[INDEX_MINUTES_ERROR] as String).takeIf { str -> str.isNotEmpty() }
                            trafficError = (it[INDEX_TRAFFIC_ERROR] as String).takeIf { str -> str.isNotEmpty() }
                        }
                    } else {
                        SimLimitState(
                            initialMinutesText = it[INDEX_INITIAL_MINUTES_TEXT] as String,
                            initialTrafficText = it[INDEX_INITIAL_TRAFFIC_TEXT] as String,
                            initialRolloverEnabled = it[INDEX_INITIAL_ROLLOVER_ENABLED] as Boolean,
                            initialTrafficMb = it[INDEX_INITIAL_TRAFFIC_MB] as Int,
                            locale = locale,
                        )
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
     * Stored text is kept as typed; parsing and normalization occur once at validation time.
     * Only [NumberInputNormalizer.isZeroValue] is used here.
     */
    fun onMinutesChange(newValue: TextFieldValue) {
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

        var text = processed.text
        if (text.isNotEmpty()) {
            while (text.length > 1 && text[0] == '0' && Character.isDigit(text[1])) {
                text = text.drop(1)
            }
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
     * Stored text is kept as typed; parsing and normalization occur once at validation time.
     * Only [NumberInputNormalizer.isZeroValue] is used here.
     */
    fun onTrafficChange(newValue: TextFieldValue) {
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

        // Replace initial zero only on single-character addition
        if (shouldReplaceInitialTrafficZero(old, newValue)) {
            val typedChar = newValue.text.last()
            if (typedChar.isDigit()) {
                val newText = typedChar.toString()
                processed = newValue.copy(text = newText, selection = TextRange(newText.length))
            }
        }

        var text = processed.text
        if (text.isNotEmpty()) {
            while (text.length > 1 && text[0] == '0' && Character.isDigit(text[1])) {
                text = text.drop(1)
            }
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
        val isInitial = NumberInputNormalizer.isZeroValue(old.text)
        return isInitial && newValue.text.length == old.text.length + 1 && newValue.text.startsWith(old.text)
    }
}

@Composable
private fun DialogFields(state: SimLimitState) {
    val minutesFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
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
                    .onFocusChanged { state.onMinutesFocusChanged(it.isFocused) },
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

@Preview
@Composable
private fun SimLimitComposeDialogPreview() {
    SimLimitComposeDialog(
        slot = 0,
        operatorName = "MTS",
        initialMinutes = 100,
        initialTrafficMb = 1024,
        initialRolloverEnabled = false,
        onDismiss = {},
        onConfirm = { _, _, _ -> },
        onReset = {},
    )
}
