package com.example.minutewatcher.feature.main.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.example.minutewatcher.core.model.R

@Composable
fun AdjustmentComposeDialog(
    currentValue: Float,
    isMinutes: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit,
) {
    var text by remember {
        mutableStateOf(if (isMinutes) currentValue.toInt().toString() else "%.1f".format(currentValue).replace(",", "."))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = if (isMinutes) R.string.remaining_text else R.string.traffic_remaining_text)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.replace(",", ".") },
                label = {
                    Text(
                        stringResource(id = if (isMinutes) R.string.minutes_limit_hint else R.string.traffic_limit_gb_hint_dialog),
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = if (isMinutes) KeyboardType.Number else KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(onClick = {
                val value = text.toFloatOrNull() ?: 0f
                onConfirm(value)
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
