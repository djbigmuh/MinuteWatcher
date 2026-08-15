package com.example.minutewatcher.feature.widget

import android.content.Context

/**
 * Контракт рендера виджетов.
 *
 * ВНИМАНИЕ: интерфейс используется跨модульно. Любое изменение сигнатуры
 * [renderAll] требует одновременного обновления:
 * 1. Всех реализаций (WidgetModule.provideWidgetRenderer, тестовые фейки).
 * 2. Всех точек вызова (WidgetUpdaterImpl.safeUpdateAll, handleLimitExceeded).
 * Несоблюдение приведёт к AbstractMethodError (бинарная несовместимость).
 *
 * Строгое соблюдение API: параметры forceSimplified и forceFull
 * передаются в MinutesGlanceWidget через SharedPreferences,
 * поскольку GlanceAppWidget.provideGlance не принимает параметров.
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
