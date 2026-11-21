package com.github.djbigmuh.minutewatcher.feature.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import javax.inject.Inject

class GlanceWidgetRenderer
    @Inject
    constructor() : WidgetRenderer {
        override suspend fun renderAll(context: Context) {
            MinutesGlanceWidget().updateAll(context)
        }
    }
