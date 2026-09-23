package com.example.minutewatcher.feature.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * Receiver for MinuteWatcher widget.
 * Inherits GlanceAppWidgetReceiver.
 * Returns MinutesGlanceWidget as glanceAppWidget.
 */
class MinutesGlanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MinutesGlanceWidget()
}
