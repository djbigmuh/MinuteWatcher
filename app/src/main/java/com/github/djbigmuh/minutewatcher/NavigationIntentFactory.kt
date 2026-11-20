package com.github.djbigmuh.minutewatcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

object NavigationIntentFactory {
    fun getAppSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }

    /**
     * Returns an Intent to open usage access settings.
     *
     * Passing package URI is required to open settings for a specific app
     * on modern Android/OEMs; without a URI, several shells block implicit intents.
     * Behavior when permission is missing on a specific device is an OEM shell issue;
     * the app must provide a fallback.
     */
    fun getUsageAccessSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
}
