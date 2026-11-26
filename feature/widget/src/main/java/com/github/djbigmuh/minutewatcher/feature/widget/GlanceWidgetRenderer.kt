package com.github.djbigmuh.minutewatcher.feature.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import com.github.djbigmuh.minutewatcher.core.common.util.AppLogger
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

class GlanceWidgetRenderer
    @Inject
    constructor() : WidgetRenderer {
        override suspend fun renderAll(context: Context) {
            @Suppress("TooGenericExceptionCaught")
            val glanceIds =
                try {
                    GlanceAppWidgetManager(context).getGlanceIds(MinutesGlanceWidget::class.java)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.w("GlanceWidgetRenderer: Failed to get glance IDs", e)
                    emptyList()
                }

            if (glanceIds.isNotEmpty()) {
                MinutesGlanceWidget().updateAll(context)
            } else {
                AppLogger.d("GlanceWidgetRenderer: No placed widget instances found, skipping update")
            }
        }
    }
