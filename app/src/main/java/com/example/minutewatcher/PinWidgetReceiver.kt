package com.example.minutewatcher

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.example.minutewatcher.core.resources.R
import com.example.minutewatcher.feature.widget.MinutesGlanceWidget
import kotlinx.coroutines.launch

class PinWidgetReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val appWidgetId =
            intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            )

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            // User canceled the widget pinning
            return
        }

        Toast
            .makeText(
                context,
                context.getString(R.string.widget_added_success),
                Toast.LENGTH_SHORT,
            ).show()

        // Trigger an immediate update for the newly added widget
        context.applicationScope.launch {
            try {
                val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
                MinutesGlanceWidget().update(context, glanceId)
            } catch (e: Exception) {
                com.example.minutewatcher.core.common.util.AppLogger
                    .e("Failed to update widget after pinning", e)
            }
        }
    }
}
