package com.example.minutewatcher.feature.settings.ui

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.WidgetSettingsLoader
import com.example.minutewatcher.core.model.Constants.SimConstants
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
         * ([WidgetSettingsViewModel.validateSettings]). Повторная валидация
         * здесь не выполняется — единая точка валидации в ViewModel исключает
         * расхождение диапазонов и дублирование логики coerceIn.
         *
         * **Строгое соблюдение API:** все диапазоны coerceIn соответствуют
         * константам [Constants.Widget.*]. Если кастомная прошивка возвращает
         * значения вне диапазонов — это проблема кастомной прошивки.
         */
        suspend fun save(
            state: WidgetSettingsState,
            skipSimSettings: Boolean = false,
        ): Boolean =
            try {
                if (state.settingsLoadFailed) {
                    AppLogger.w("Skipping save in WidgetSettingsSaver: settings were not loaded successfully")
                    return false
                }

                // Строгое соблюдение API: индексы слотов должны находиться в диапазоне
                // SimConstants.SUPPORTED_SLOT_INDICES. Фильтрация защищает от записи
                // настроек для несуществующих слотов при некорректном вводе из UI.
                val validatedState =
                    state.copy(
                        simStates = state.simStates.filterKeys { it in SimConstants.SUPPORTED_SLOT_INDICES },
                    )

                loader.save(validatedState, skipSimSettings = skipSimSettings)
                dataChangeNotifier.notifyDataChanged(updateWidget = true, force = true)
                true
            } catch (e: CancellationException) {
                // Пробрасываем для кооперативной отмены корутины.
                // Строгое соблюдение API kotlinx.coroutines: CancellationException
                // должен пробрасываться для корректной отмены scope.
                throw e
            } catch (e: Exception) {
                AppLogger.e("Failed to save widget settings", e)
                false
            }
    }
