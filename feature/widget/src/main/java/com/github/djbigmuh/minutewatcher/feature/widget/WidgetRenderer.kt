package com.github.djbigmuh.minutewatcher.feature.widget

import android.content.Context

/**
 * Widget renderer contract.
 */
interface WidgetRenderer {
    /**
     * Triggers the rendering of all widgets.
     *
     * @param context The application context.
     */
    suspend fun renderAll(context: Context)
}
