package com.example.minutewatcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

object NavigationIntentFactory {
    fun getAppSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }

    fun getUsageAccessSettingsIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
}
