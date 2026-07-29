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
    val minutesRangeMsg = stringResource(
        id = R.string.validation_error_invalid_range,
        "0",
        Constants.MAX_MINUTES_LIMIT.toString(),
    )
    val trafficRangeMsg =
        stringResource(
            id = R.string.validation_error_invalid_range,
            "0",
            Constants.MAX_TRAFFIC_LIMIT_GB.toInt().toString(),
        )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.limits_for_operator, operatorName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = minutesText,
                    onValueChange = {
                        if (it.length <= 6) {
                            minutesText = it.filter { char -> char.isDigit() }
                            minutesError = null
                        }
                    },
                    label = { Text(stringResource(id = R.string.set_minutes_limit)) },
                    placeholder = { Text(stringResource(id = R.string.minutes_limit_hint)) },
                    isError = minutesError != null,
                    supportingText = { if (minutesError != null) Text(text = minutesError!!) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = trafficText,
                    onValueChange = {
                        if (it.length <= 10) {
                            trafficText = it.replace(",", ".")
                            trafficError = null
                        }
                    },
                    label = { Text(stringResource(id = R.string.set_traffic_limit)) },
                    placeholder = { Text(stringResource(id = R.string.traffic_limit_gb_hint_dialog)) },
                    isError = trafficError != null,
                    supportingText = { if (trafficError != null) Text(text = trafficError!!) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = rolloverEnabled,
                        onCheckedChange = { rolloverEnabled = it },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(id = R.string.rollover_label),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        val min = minutesText.toIntOrNull()
                        val trafGb = trafficText.toFloatOrNull()

                        var hasError = false
                        if (minutesText.isNotEmpty() && min == null) {
                            minutesError = invalidNumberMsg
                            hasError = true
                        } else if (min != null && (min < 0 || min > Constants.MAX_MINUTES_LIMIT)) {
                            minutesError = minutesRangeMsg
                            hasError = true
                        }

                        if (trafficText.isNotEmpty() && trafGb == null) {
                            trafficError = invalidNumberMsg
                            hasError = true
                        } else if (trafGb != null && (trafGb < 0 || trafGb > Constants.MAX_TRAFFIC_LIMIT_GB)) {
                            trafficError = trafficRangeMsg
                            hasError = true
                        }

                        if (!hasError) {
                            onConfirm(min ?: 0, ((trafGb ?: 0f) * Constants.MB_PER_GB).toInt(), rolloverEnabled)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(id = R.string.set_button))
                }

                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(id = R.string.reset_limits_button))
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(id = R.string.cancel_button))
                }
            }
        },
        dismissButton = null,
    )
}

