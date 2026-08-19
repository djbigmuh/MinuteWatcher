package com.example.minutewatcher.feature.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * Receiver для виджета MinuteWatcher.
 * Наследует GlanceAppWidgetReceiver.
 * Возвращает MinutesGlanceWidget как glanceAppWidget.
 */
class MinutesGlanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MinutesGlanceWidget()
}
