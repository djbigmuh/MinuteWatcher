package com.example.minutewatcher.feature.main

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
import com.example.minutewatcher.core.model.R

data class TrackingBannerState(
    val showPhonePermissionBanner: Boolean = false,
    val isPhonePermissionPermanentlyDenied: Boolean = false,
    val showUsageAccessBanner: Boolean = false,
    val showTrafficErrorBanner: Boolean = false,
    val showAppSearchErrorBanner: Boolean = false,
) {
    val hasAnyError: Boolean
        get() = showPhonePermissionBanner || showUsageAccessBanner || showTrafficErrorBanner || showAppSearchErrorBanner
}

@Suppress("ktlint:standard:function-naming")
@Composable
fun EmptyState() {
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
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
fun ErrorState(message: String) {
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
        Text(text = message, style = MaterialTheme.typography.bodyMedium)
    }
}
