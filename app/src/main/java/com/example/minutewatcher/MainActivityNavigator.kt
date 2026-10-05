package com.example.minutewatcher

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
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
        val intent = NavigationIntentFactory.getAppSettingsIntent(context)
        if (context.packageManager.resolveActivity(intent, 0) != null) {
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                AppLogger.e("AppSettings activity not found", e)
            }
        } else {
            AppLogger.e("No activity resolved for AppSettings intent")
        }
    }

    fun openUsageAccessSettings(context: Context) {
        // Priority: "Usage Access" screen for the specific app.
        val usageIntent = NavigationIntentFactory.getUsageAccessSettingsIntent(context)
        if (context.packageManager.resolveActivity(usageIntent, 0) != null) {
            try {
                context.startActivity(usageIntent)
                return
            } catch (e: ActivityNotFoundException) {
                AppLogger.w("Package-scoped usage access settings unavailable, trying generic list", e)
            } catch (e: Exception) {
                AppLogger.w("Package-scoped usage access settings failed, trying generic list", e)
            }
        } else {
            AppLogger.w("Package-scoped usage access settings intent not resolved, trying generic list")
        }

        // Fallback: generic list of apps with usage access.
        val genericIntent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        if (context.packageManager.resolveActivity(genericIntent, 0) != null) {
            try {
                context.startActivity(genericIntent)
                return
            } catch (e: ActivityNotFoundException) {
                AppLogger.e("Usage access settings activity not found, opening app details", e)
            } catch (e: Exception) {
                AppLogger.e("Usage access settings failed, opening app details", e)
            }
        } else {
            AppLogger.e("Generic usage access settings intent not resolved, opening app details")
        }

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
