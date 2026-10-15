package com.example.minutewatcher

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.example.minutewatcher.core.android.util.AppLocaleContexts
import com.example.minutewatcher.core.resources.R
import com.example.minutewatcher.feature.widget.MinutesGlanceWidget
import kotlinx.coroutines.CoroutineDispatcher

open class PinWidgetReceiver : BroadcastReceiver() {
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

        val successMessage = resolveSuccessMessage(context)
        showSuccessToast(context, successMessage)

        // Trigger an immediate update for the newly added widget
        goAsyncWithScope(context, acquirePendingResult(), dispatcher = asyncDispatcher()) { appContext ->
            updateWidget(appContext, appWidgetId)
        }
    }

    @VisibleForTesting
    protected open fun resolveSuccessMessage(context: Context): String {
        val localizedContext = AppLocaleContexts.forAppLocale(context)
        return localizedContext.getString(R.string.widget_added_success)
    }

    @VisibleForTesting
    protected open fun showSuccessToast(
        context: Context,
        message: String,
    ) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    @VisibleForTesting
    protected open fun acquirePendingResult(): PendingResult = goAsync()

    @VisibleForTesting
    protected open fun asyncDispatcher(): CoroutineDispatcher? = null

    @VisibleForTesting
    protected open suspend fun updateWidget(
        appContext: Context,
        appWidgetId: Int,
    ) {
        val glanceId = GlanceAppWidgetManager(appContext).getGlanceIdBy(appWidgetId)
        MinutesGlanceWidget().update(appContext, glanceId)
    }
}
