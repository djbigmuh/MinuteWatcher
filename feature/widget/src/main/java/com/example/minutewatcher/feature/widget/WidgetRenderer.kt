package com.example.minutewatcher.feature.widget

import android.content.Context
import com.example.minutewatcher.core.model.WidgetSettingsState

/**
 * Контракт рендера виджетов.
 *
 * **Строгое соблюдение API:** режим рендера (упрощённый / полный)
 * определяется внутри [MinutesGlanceWidget.provideGlance] на основе
 * [WidgetSettingsState.widgetLimitExceeded]. Внешние флаги не передаются,
 * что исключает race condition при асинхронном вызове provideGlance().
 *
 * **Отклонение кастомной прошивки:** если кастомная прошивка вызывает
 * provideGlance() вне ожидаемого lifecycle — это проблема кастомной прошивки.
 */
interface WidgetRenderer {
    /**
     * Triggers the rendering of all widgets.
     *
     * @param context The application context.
     */
    suspend fun renderAll(context: Context)
}
