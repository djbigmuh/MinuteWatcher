package com.github.djbigmuh.minutewatcher.feature.widget

import android.content.Context

/**
 * Widget renderer contract.
 *
 * Strict API compliance: rendering mode (simplified / full)
 * is determined inside MinutesGlanceWidget.provideGlance based on
 * WidgetSettingsState.widgetLimitExceeded.
 *
 * To eliminate race conditions during concurrent updates (e.g.,
 * forced manual update during background probe), mode is passed
 * via MinutesGlanceWidget(forceFullRender = ...) instance.
 * Each render pass uses its isolated widget instance.
 */
interface WidgetRenderer {
    /**
     * Triggers the rendering of all widgets.
     *
     * @param context The application context.
     * @param forceFull If true, forces the full layout rendering regardless of global limit settings.
     */
    suspend fun renderAll(
        context: Context,
        forceFull: Boolean = false,
    )
}
