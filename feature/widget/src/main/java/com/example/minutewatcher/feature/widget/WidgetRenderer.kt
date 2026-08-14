package com.example.minutewatcher.feature.widget

import android.content.Context

/**
 * Interface for rendering the widget content.
 *
 * IMPORTANT: This interface is used across modules. Any change to the signature of [renderAll]
 * MUST be reflected in all implementations and call sites to avoid binary incompatibility
 * (e.g., AbstractMethodError).
 */
interface WidgetRenderer {
    /**
     * Triggers the rendering of all widgets.
     *
     * @param context The application context.
     * @param forceSimplified Whether to force a simplified layout (e.g., when close to memory limits).
     * @param forceFull Whether to force a full layout (e.g., ignoring cached limits for a single update).
     */
    suspend fun renderAll(
        context: Context,
        forceSimplified: Boolean,
        forceFull: Boolean,
    )
}
