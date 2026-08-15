package com.example.minutewatcher.feature.settings.ui

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.WidgetSettingsLoader
import com.example.minutewatcher.core.model.WidgetSettingsState
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WidgetSettingsSaver
    @Inject
    constructor(
        private val loader: WidgetSettingsLoader,
        private val dataChangeNotifier: DataChangeNotifier,
    ) {
        /**
         * Сохраняет настройки виджета.
         *
         * **Предусловие:** [state] уже провалидирован вызывающей стороной
         * ([WidgetSettingsViewModel.saveAndNotify]). Повторная валидация здесь
         * не выполняется — единая точка валидации исключает расхождение диапазонов.
         *
         * **Строгое соблюдение API:** все диапазоны coerceIn соответствуют
         * константам [Constants.Widget.*]. Если кастомная прошивка возвращает
         * значения вне диапазона — это проблема кастомной прошивки.
         */
        suspend fun save(state: WidgetSettingsState): Boolean =
            try {
                if (state.settingsLoadFailed) {
                    AppLogger.w("Skipping save in WidgetSettingsSaver: settings were not loaded successfully")
                    return false
                }
                loader.save(state)
                dataChangeNotifier.notifyDataChanged(updateWidget = true, force = true)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Failed to save widget settings", e)
                false
            }
    }
