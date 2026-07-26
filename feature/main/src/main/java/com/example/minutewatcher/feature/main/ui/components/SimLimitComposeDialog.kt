package com.example.minutewatcher.feature.main.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.R

@Composable
fun SimLimitComposeDialog(
    operatorName: String,
    initialMinutes: Int,
    initialTrafficMb: Int,
    onDismiss: () -> Unit,
    onConfirm: (minutes: Int, trafficMb: Int) -> Unit,
    onReset: () -> Unit,
) {
    var minutesText by remember { mutableStateOf(if (initialMinutes > 0) initialMinutes.toString() else "") }
    var trafficText by remember {
        val gb = initialTrafficMb / Constants.MB_PER_GB
        mutableStateOf(if (gb > 0) "%.1f".format(gb).replace(",", ".") else "")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.limits_for_operator, operatorName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = minutesText,
                    onValueChange = { if (it.length <= 5) minutesText = it.filter { char -> char.isDigit() } },
                    label = { Text(stringResource(id = R.string.set_minutes_limit)) },
                    placeholder = { Text(stringResource(id = R.string.minutes_limit_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = trafficText,
                    onValueChange = { if (it.length <= 8) trafficText = it.replace(",", ".") },
                    label = { Text(stringResource(id = R.string.set_traffic_limit)) },
                    placeholder = { Text(stringResource(id = R.string.traffic_limit_gb_hint_dialog)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        val min = minutesText.toIntOrNull()?.coerceIn(0, Constants.MAX_MINUTES_LIMIT) ?: 0
                        val trafGb = trafficText.toFloatOrNull()?.coerceIn(0f, Constants.MAX_TRAFFIC_LIMIT_GB) ?: 0f
                        onConfirm(min, (trafGb * Constants.MB_PER_GB).toInt())
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
