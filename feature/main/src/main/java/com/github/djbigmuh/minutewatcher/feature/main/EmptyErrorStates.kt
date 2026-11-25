package com.github.djbigmuh.minutewatcher.feature.main

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.djbigmuh.minutewatcher.core.designsystem.component.AppButton
import com.github.djbigmuh.minutewatcher.core.resources.R

/**
 * Displayed when no SIM cards are detected or permissions are missing.
 * Informs the user that the app could not access SIM data,
 * and suggests navigating to grant the necessary permission (READ_PHONE_STATE).
 *
 * Screen layout:
 * - Header: Message indicating no SIM cards found.
 * - Description: Explanation of permission check requirement.
 * - Action button: Initiates permission request process.
 *
 * @param onGrantPermission Callback to request phone state permission.
 */
@Composable
fun EmptyState(onGrantPermission: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(id = R.string.no_sim_found),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(id = R.string.check_permissions_sim),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(16.dp))
        AppButton(
            text = stringResource(id = R.string.action_grant),
            onClick = onGrantPermission,
        )
    }
}

/**
 * Displayed when a critical data loading error or repository failure occurs.
 * Allows the user to view the error description and attempt to reload the data.
 *
 * Screen layout:
 * - Header: Standard error message.
 * - Description: Detailed error text passed via [messageRes] parameter.
 * - Action button: Triggers data reload process via [onRetry].
 *
 * @param messageRes Resource ID for the text description of the error.
 * @param onRetry Callback to retry data loading or reset error state.
 */
@Composable
fun ErrorState(
    @StringRes messageRes: Int,
    onRetry: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(id = R.string.error),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = stringResource(id = messageRes), style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(16.dp))
        AppButton(
            text = stringResource(id = R.string.action_retry),
            onClick = onRetry,
        )
    }
}
