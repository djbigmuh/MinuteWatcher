package com.example.minutewatcher.feature.widget

import android.content.Context

/**
 * Контракт рендера виджетов.
 *
 * Строгое соблюдение API: режим рендера (упрощённый / полный)
 * определяется внутри MinutesGlanceWidget.provideGlance на основе
 * WidgetSettingsState.widgetLimitExceeded. Внешние флаги не передаются,
 * что исключает race condition.
 *
 * Отклонение кастомной прошивки: если кастомная прошивка вызывает
 * provideGlance() вне ожидаемого lifecycle — это проблема кастомной прошивки.
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
