package com.example.minutewatcher.feature.main.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.minutewatcher.core.resources.R

@Composable
fun ResetConfirmationDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    @StringRes titleRes: Int = R.string.reset_confirmation_title,
    @StringRes textRes: Int = R.string.reset_confirmation_body,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = titleRes)) },
        text = { Text(stringResource(id = textRes)) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(id = R.string.reset_label))
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
fun SimpleMessageDialog(
    title: String,
    text: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(id = R.string.action_ok))
            }
        },
    )
}

@Composable
fun UsageAccessDialog(
    onDismiss: () -> Unit,
    onSettings: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.usage_access_dialog_title)) },
        text = { Text(stringResource(id = R.string.usage_access_dialog_description)) },
        confirmButton = {
            Button(onClick = onSettings) {
                Text(stringResource(id = R.string.action_settings))
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
fun OnboardingDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.onboarding_title)) },
        text = { Text(stringResource(id = R.string.onboarding_message)) },
        confirmButton = {
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(id = R.string.onboarding_button))
            }
        },
    )
}

@Composable
fun AboutDialog(
    versionName: String,
    onEmailAuthor: () -> Unit,
    onDismiss: () -> Unit,
    versionCode: Int = 1,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.about_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(id = R.string.about_message))
                Text(
                    stringResource(
                        id = R.string.about_version_format,
                        versionName,
                        versionCode,
                    ),
                )
                Text(stringResource(id = R.string.about_author_name))
                val email = stringResource(id = R.string.about_author_email)
                val emailLabel = stringResource(id = R.string.about_email_label)
                Row(
                    modifier = Modifier.clickable(onClick = onEmailAuthor),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(emailLabel)
                    Text(email)
                }
                Text(stringResource(id = R.string.about_signoff))
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(id = R.string.action_ok))
            }
        },
    )
}
