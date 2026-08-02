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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.resources.R

@Composable
fun SimLimitComposeDialog(
    operatorName: String,
    initialMinutes: Int,
    initialTrafficMb: Int,
    initialRolloverEnabled: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (minutes: Int, trafficMb: Int, rollover: Boolean) -> Unit,
    onReset: () -> Unit,
) {
    val state = rememberSimLimitState(initialMinutes, initialTrafficMb, initialRolloverEnabled)

    val invalidNumberMsg = stringResource(id = R.string.validation_error_invalid_number)
    val minutesRangeMsg = stringResource(id = R.string.validation_error_invalid_range, "0", Constants.MAX_MINUTES_LIMIT.toString())
    val trafficRangeMsg =
        stringResource(id = R.string.validation_error_invalid_range, "0", Constants.MAX_TRAFFIC_LIMIT_GB.toInt().toString())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.limits_for_operator, operatorName)) },
        text = {
            DialogFields(
                state = state,
            )
        },
        confirmButton = {
            DialogButtons(
                onConfirm = {
                    val (min, trafGb, minErr, trafErr) =
                        SimLimitValidator.validate(
                            state.minutesText,
                            state.trafficText,
                            invalidNumberMsg,
                            minutesRangeMsg,
                            trafficRangeMsg,
                        )
                    state.minutesError = minErr
                    state.trafficError = trafErr
                    if (minErr == null && trafErr == null) {
                        onConfirm(min ?: 0, ((trafGb ?: 0f) * Constants.MB_PER_GB).toInt(), state.rolloverEnabled)
                    }
                },
                onReset = onReset,
                onDismiss = onDismiss,
            )
        },
        dismissButton = null,
    )
}

@Composable
private fun rememberSimLimitState(
    initialMinutes: Int,
    initialTrafficMb: Int,
    initialRolloverEnabled: Boolean,
): SimLimitState {
    val initialTrafficText = remember(initialTrafficMb) {
        val gb = initialTrafficMb / Constants.MB_PER_GB
        if (gb > 0) "%.1f".format(gb).replace(",", ".") else ""
    }

    return remember {
        SimLimitState(
            initialMinutesText = if (initialMinutes > 0) initialMinutes.toString() else "",
            initialTrafficText = initialTrafficText,
            initialRolloverEnabled = initialRolloverEnabled,
        )
    }
}

private class SimLimitState(
    initialMinutesText: String,
    initialTrafficText: String,
    initialRolloverEnabled: Boolean,
) {
    var minutesText by mutableStateOf(initialMinutesText)
    var trafficText by mutableStateOf(initialTrafficText)
    var rolloverEnabled by mutableStateOf(initialRolloverEnabled)
    var minutesError by mutableStateOf<String?>(null)
    var trafficError by mutableStateOf<String?>(null)

    fun onMinutesChange(text: String) {
        minutesText = text
        minutesError = null
    }

    fun onTrafficChange(text: String) {
        trafficText = text
        trafficError = null
    }
}

@Composable
private fun DialogFields(
    state: SimLimitState,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = state.minutesText,
            onValueChange = { if (it.length <= 6) state.onMinutesChange(it.filter { c -> c.isDigit() }) },
            label = { Text(stringResource(id = R.string.set_minutes_limit)) },
            placeholder = { Text(stringResource(id = R.string.minutes_limit_hint)) },
            isError = state.minutesError != null,
            supportingText = { if (state.minutesError != null) Text(text = state.minutesError!!) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.trafficText,
            onValueChange = { if (it.length <= 10) state.onTrafficChange(it.replace(",", ".")) },
            label = { Text(stringResource(id = R.string.set_traffic_limit)) },
            placeholder = { Text(stringResource(id = R.string.traffic_limit_gb_hint_dialog)) },
            isError = state.trafficError != null,
            supportingText = { if (state.trafficError != null) Text(text = state.trafficError!!) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
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
