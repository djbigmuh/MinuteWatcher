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
    var minutesText by remember { mutableStateOf(if (initialMinutes > 0) initialMinutes.toString() else "") }
    var trafficText by remember {
        val gb = initialTrafficMb / Constants.MB_PER_GB
        mutableStateOf(if (gb > 0) "%.1f".format(gb).replace(",", ".") else "")
    }
    var rolloverEnabled by remember { mutableStateOf(initialRolloverEnabled) }
    var minutesError by remember { mutableStateOf<String?>(null) }
    var trafficError by remember { mutableStateOf<String?>(null) }

    val invalidNumberMsg = stringResource(id = R.string.validation_error_invalid_number)
    val minutesRangeMsg = stringResource(id = R.string.validation_error_invalid_range, "0", Constants.MAX_MINUTES_LIMIT.toString())
    val trafficRangeMsg = stringResource(id = R.string.validation_error_invalid_range, "0", Constants.MAX_TRAFFIC_LIMIT_GB.toInt().toString())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.limits_for_operator, operatorName)) },
        text = {
            DialogFields(
                minutesText = minutesText,
                trafficText = trafficText,
                rolloverEnabled = rolloverEnabled,
                minutesError = minutesError,
                trafficError = trafficError,
                onMinutesChange = { minutesText = it; minutesError = null },
                onTrafficChange = { trafficText = it; trafficError = null },
                onRolloverChange = { rolloverEnabled = it }
            )
        },
        confirmButton = {
            DialogButtons(
                onConfirm = {
                    val (min, trafGb, minErr, trafErr) = validateLimits(minutesText, trafficText, invalidNumberMsg, minutesRangeMsg, trafficRangeMsg)
                    minutesError = minErr
                    trafficError = trafErr
                    if (minErr == null && trafErr == null) {
                        onConfirm(min ?: 0, ((trafGb ?: 0f) * Constants.MB_PER_GB).toInt(), rolloverEnabled)
                    }
                },
                onReset = onReset,
                onDismiss = onDismiss
            )
        },
        dismissButton = null,
    )
}

@Composable
private fun DialogFields(
    minutesText: String,
    trafficText: String,
    rolloverEnabled: Boolean,
    minutesError: String?,
    trafficError: String?,
    onMinutesChange: (String) -> Unit,
    onTrafficChange: (String) -> Unit,
    onRolloverChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = minutesText,
            onValueChange = { if (it.length <= 6) onMinutesChange(it.filter { c -> c.isDigit() }) },
            label = { Text(stringResource(id = R.string.set_minutes_limit)) },
            placeholder = { Text(stringResource(id = R.string.minutes_limit_hint)) },
            isError = minutesError != null,
            supportingText = { if (minutesError != null) Text(text = minutesError) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = trafficText,
            onValueChange = { if (it.length <= 10) onTrafficChange(it.replace(",", ".")) },
            label = { Text(stringResource(id = R.string.set_traffic_limit)) },
            placeholder = { Text(stringResource(id = R.string.traffic_limit_gb_hint_dialog)) },
            isError = trafficError != null,
            supportingText = { if (trafficError != null) Text(text = trafficError) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = rolloverEnabled, onCheckedChange = onRolloverChange)
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

private data class ValidationResult(
    val minutes: Int?,
    val trafficGb: Float?,
    val minutesError: String?,
    val trafficError: String?,
)

private fun validateLimits(
    minutesText: String,
    trafficText: String,
    invalidNumberMsg: String,
    minutesRangeMsg: String,
    trafficRangeMsg: String,
): ValidationResult {
    val min = minutesText.toIntOrNull()
    val trafGb = trafficText.toFloatOrNull()

    var minErr: String? = null
    if (minutesText.isNotEmpty() && min == null) {
        minErr = invalidNumberMsg
    } else if (min != null && (min < 0 || min > Constants.MAX_MINUTES_LIMIT)) {
        minErr = minutesRangeMsg
    }

    var trafErr: String? = null
    if (trafficText.isNotEmpty() && trafGb == null) {
        trafErr = invalidNumberMsg
    } else if (trafGb != null && (trafGb < 0 || trafGb > Constants.MAX_TRAFFIC_LIMIT_GB)) {
        trafErr = trafficRangeMsg
    }

    return ValidationResult(min, trafGb, minErr, trafErr)
}
