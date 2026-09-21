package com.example.minutewatcher

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.resources.R
import com.example.minutewatcher.feature.widget.MinutesGlanceWidgetReceiver

/**
 * Helper for MainActivity navigation and widget management.
 */
object MainActivityNavigator {
    fun openWidgetSettings(context: Context) {
        context.startActivity(Intent(context, WidgetSettingsActivity::class.java))
    }

    fun openAppSettings(context: Context) {
        context.startActivity(NavigationIntentFactory.getAppSettingsIntent(context))
    }

    fun openUsageAccessSettings(context: Context) {
        // Priority: "Usage Access" screen for the specific app.
        try {
            context.startActivity(NavigationIntentFactory.getUsageAccessSettingsIntent(context))
            return
        } catch (e: Exception) {
            AppLogger.w("Package-scoped usage access settings unavailable, trying generic list", e)
        }
        // Fallback: generic list of apps with usage access.
        try {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return
        } catch (e: Exception) {
            AppLogger.e("Usage access settings not found, opening app details", e)
        }
        openAppSettings(context)
    }

    fun addWidgetToHomeScreen(context: Context) {
        val appWidgetManager = context.getSystemService(AppWidgetManager::class.java)
        val myProvider = ComponentName(context, MinutesGlanceWidgetReceiver::class.java)

        val existingIds = appWidgetManager.getAppWidgetIds(myProvider)
        if (existingIds.isNotEmpty()) {
            Toast.makeText(context, context.getString(R.string.widget_already_added), Toast.LENGTH_SHORT).show()
            return
        }

        if (appWidgetManager.isRequestPinAppWidgetSupported) {
            val callbackIntent = Intent(context, PinWidgetReceiver::class.java)
            val successCallback =
                PendingIntent.getBroadcast(
                    context,
                    0,
                    callbackIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            appWidgetManager.requestPinAppWidget(myProvider, null, successCallback)
        } else {
            Toast.makeText(context, context.getString(R.string.widget_pin_unsupported), Toast.LENGTH_SHORT).show()
        }
    }
}
