package com.github.djbigmuh.minutewatcher.feature.main.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.djbigmuh.minutewatcher.core.resources.R

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
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = null,
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Surface(
                    modifier = Modifier
                        .size(64.dp)
                        .align(Alignment.CenterHorizontally),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "MW",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(id = R.string.onboarding_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(id = R.string.about_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                OnboardingStep(
                    number = "1",
                    title = stringResource(id = R.string.settings_widget_title),
                    description = stringResource(id = R.string.onboarding_message),
                )
                OnboardingStep(
                    number = "2",
                    title = stringResource(id = R.string.usage_access_dialog_title),
                    description = stringResource(id = R.string.usage_access_dialog_description),
                )
                OnboardingStep(
                    number = "3",
                    title = stringResource(id = R.string.add_widget_button),
                    description = stringResource(id = R.string.widget_description),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(stringResource(id = R.string.onboarding_button))
            }
        },
    )
}

@Composable
private fun OnboardingStep(
    number: String,
    title: String,
    description: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                modifier = Modifier.size(30.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = number,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun AboutDialog(
    versionName: String,
    onEmailAuthor: () -> Unit,
    onDismiss: () -> Unit,
    versionCode: Int,
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
                Text(stringResource(id = R.string.about_translation_feedback))
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

@Preview
@Composable
private fun UsageAccessDialogPreview() {
    UsageAccessDialog(onDismiss = {}, onSettings = {})
}

@Preview
@Composable
private fun OnboardingDialogPreview() {
    OnboardingDialog(onDismiss = {})
}

@Preview
@Composable
private fun AboutDialogPreview() {
    AboutDialog(versionName = "3.3.0", versionCode = 330, onEmailAuthor = {}, onDismiss = {})
}
