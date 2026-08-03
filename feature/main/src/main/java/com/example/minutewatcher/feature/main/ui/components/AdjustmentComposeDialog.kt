package com.example.minutewatcher.feature.main.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.resources.R

@Composable
fun AdjustmentComposeDialog(
    currentValue: Float,
    isMinutes: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit,
) {
    val initialValue = maxOf(0f, currentValue)
    var text by remember {
        mutableStateOf(if (isMinutes) initialValue.toInt().toString() else "%.1f".format(initialValue).replace(",", "."))
    }

    var errorText by remember { mutableStateOf<String?>(null) }

    val maxLimit = if (isMinutes) Constants.MAX_MINUTES_LIMIT.toFloat() else Constants.MAX_TRAFFIC_LIMIT_GB

    val invalidNumberMsg = stringResource(id = R.string.validation_error_invalid_number)
    val invalidRangeMsg = stringResource(id = R.string.validation_error_invalid_range, "0", maxLimit.toInt().toString())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = if (isMinutes) R.string.remaining_text else R.string.traffic_remaining_text)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it.replace(",", ".")
                        errorText = null
                    },
                    label = {
                        Text(
                            stringResource(id = if (isMinutes) R.string.minutes_limit_hint else R.string.traffic_limit_gb_hint_dialog),
                        )
                    },
                    isError = errorText != null,
                    supportingText = {
                        errorText?.let {
                            Text(text = it)
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = if (isMinutes) KeyboardType.Number else KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val value = text.toFloatOrNull()
                if (value == null) {
                    errorText = invalidNumberMsg
                } else if (value < 0 || value > maxLimit) {
                    errorText = invalidRangeMsg
                } else {
                    onConfirm(value)
                }
            }) {
                Text(stringResource(id = R.string.save_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = R.string.cancel_button))
            }
        },
    )
}
