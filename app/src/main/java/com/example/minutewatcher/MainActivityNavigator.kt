package com.example.minutewatcher

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.resources.R
import com.example.minutewatcher.feature.widget.MinutesGlanceWidgetReceiver

/**
 * Helper for MainActivity navigation and widget management.
 */
object MainActivityNavigator {
    @VisibleForTesting
    internal var startActivityOverride: ((Context, Intent) -> Unit)? = null

    private fun launchActivity(
        context: Context,
        intent: Intent,
    ) {
        val override = startActivityOverride
        if (override != null) {
            override(context, intent)
        } else {
            context.startActivity(intent)
        }
    }

    fun openWidgetSettings(context: Context) {
        context.startActivity(Intent(context, WidgetSettingsActivity::class.java))
    }

    /**
     * Opens application settings directly.
     * Logs an error if ActivityNotFoundException or SecurityException is thrown.
     */
    fun openAppSettings(context: Context) {
        val intent = NavigationIntentFactory.getAppSettingsIntent(context)
        try {
            launchActivity(context, intent)
        } catch (e: ActivityNotFoundException) {
            AppLogger.e("AppSettings activity not found", e)
        } catch (e: SecurityException) {
            AppLogger.e("AppSettings security exception", e)
        }
    }

    /**
     * Opens usage access settings directly, falling back to the generic usage access list
     * and finally to app settings if specific intents cannot be launched.
     */
    fun openUsageAccessSettings(context: Context) {
        // Priority: Usage access settings for the specific app package.
        val usageIntent = NavigationIntentFactory.getUsageAccessSettingsIntent(context)
        try {
            launchActivity(context, usageIntent)
            return
        } catch (e: ActivityNotFoundException) {
            AppLogger.w("Package-scoped usage access settings unavailable, trying generic list", e)
        } catch (e: SecurityException) {
            AppLogger.w("Package-scoped usage access settings security exception, trying generic list", e)
        }

        // Fallback 1: Generic usage access settings list.
        val genericIntent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        try {
            launchActivity(context, genericIntent)
            return
        } catch (e: ActivityNotFoundException) {
            AppLogger.w("Generic usage access settings unavailable, opening app settings", e)
        } catch (e: SecurityException) {
            AppLogger.w("Generic usage access settings security exception, opening app settings", e)
        }

        // Fallback 2: Application details settings.
        openAppSettings(context)
    }

    fun addWidgetToHomeScreen(context: Context) {
        val appWidgetManager = context.getSystemService(AppWidgetManager::class.java)
        if (appWidgetManager == null) {
            AppLogger.w("MainActivityNavigator: AppWidgetManager is unavailable on this device")
            Toast.makeText(context, context.getString(R.string.widget_pin_unsupported), Toast.LENGTH_SHORT).show()
            return
        }

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
