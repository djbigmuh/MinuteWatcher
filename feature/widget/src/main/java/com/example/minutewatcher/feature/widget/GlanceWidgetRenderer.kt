package com.example.minutewatcher.feature.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import javax.inject.Inject

// Единственная реализация WidgetRenderer. Создаётся через Hilt.
// Ручное создание WidgetUpdaterImpl с другим renderer запрещено:
// это приведёт к расхождению реализаций рендера виджета.
class GlanceWidgetRenderer
    @Inject
    constructor() : WidgetRenderer {
        override suspend fun renderAll(
            context: Context,
            forceFull: Boolean,
        ) {
            MinutesGlanceWidget.isProbeActive = forceFull
            try {
                MinutesGlanceWidget().updateAll(context)
            } finally {
                MinutesGlanceWidget.isProbeActive = false
            }
        }
    }
