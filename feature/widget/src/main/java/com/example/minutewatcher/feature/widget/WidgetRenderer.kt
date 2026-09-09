package com.example.minutewatcher.feature.widget

import android.content.Context

/**
 * Контракт рендера виджетов.
 *
 * Строгое соблюдение API: режим рендера (упрощённый / полный)
 * определяется внутри MinutesGlanceWidget.provideGlance на основе
 * WidgetSettingsState.widgetLimitExceeded.
 *
 * Для исключения race condition при параллельных обновлениях (например,
 * форсированное ручное обновление во время фонового probe), режим
 * передаётся через экземпляр MinutesGlanceWidget(forceFullRender = ...).
 * Каждый рендер-проход использует свой изолированный экземпляр виджета.
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
