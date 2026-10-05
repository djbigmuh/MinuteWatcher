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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.example.minutewatcher.core.common.util.FormatUtils
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.resources.R
import java.util.Locale

@Composable
fun AdjustmentComposeDialog(
    currentValue: Float,
    isMinutes: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit,
) {
    val locale = FormatUtils.getLocale(LocalContext.current)
    val initialValue = maxOf(0f, currentValue)
    val initialText =
        remember(currentValue, isMinutes, locale) {
            if (isMinutes) {
                initialValue.toInt().toString()
            } else {
                // Format with the effective UI locale to display the user's localized decimal separator.
                String.format(locale, "%.1f", initialValue)
            }
        }

    val state = rememberSaveable(initialText, saver = AdjustmentInputState.Saver) { AdjustmentInputState(initialText) }

    val maxLimit = if (isMinutes) Constants.MAX_MINUTES_LIMIT.toFloat() else Constants.MAX_TRAFFIC_LIMIT_GB

    val invalidNumberMsg = stringResource(id = R.string.validation_error_invalid_number)
    val invalidRangeMsg =
        stringResource(
            id = R.string.validation_error_invalid_range,
            "0",
            maxLimit.toInt().toString(),
        )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(id = if (isMinutes) R.string.remaining_text else R.string.traffic_remaining_text),
            )
        },
        text = {
            AdjustmentDialogContent(
                state = state,
                isMinutes = isMinutes,
                locale = locale,
            )
        },
        confirmButton = {
            Button(onClick = {
                when (
                    val result =
                        AdjustmentValidator.validate(
                            text = state.text.text,
                            isMinutes = isMinutes,
                            locale = locale,
                            invalidNumberMsg = invalidNumberMsg,
                            invalidRangeMsg = invalidRangeMsg,
                        )
                ) {
                    is AdjustmentValidationResult.Valid -> onConfirm(result.value)
                    is AdjustmentValidationResult.Invalid -> state.errorText = result.errorText
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

@Composable
private fun AdjustmentDialogContent(
    state: AdjustmentInputState,
    isMinutes: Boolean,
    locale: Locale,
) {
    Column {
        OutlinedTextField(
            value = state.text,
            onValueChange = { state.onTextChange(it, locale) },
            label = {
                Text(
                    stringResource(
                        id =
                            if (isMinutes) {
                                R.string.minutes_limit_hint
                            } else {
                                R.string.traffic_limit_gb_hint_dialog
                            },
                    ),
                )
            },
            isError = state.errorText != null,
            supportingText = {
                state.errorText?.let {
                    Text(text = it)
                }
            },
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = if (isMinutes) KeyboardType.Number else KeyboardType.Decimal,
                ),
            suffix = {
                Text(
                    text =
                        stringResource(
                            id = if (isMinutes) R.string.unit_minutes else R.string.unit_gigabytes,
                        ),
                )
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .onFocusChanged { state.onFocusChanged(it.isFocused) },
        )
    }
}
