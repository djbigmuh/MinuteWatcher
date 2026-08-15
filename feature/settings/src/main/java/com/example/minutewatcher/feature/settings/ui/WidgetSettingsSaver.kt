package com.example.minutewatcher.feature.settings.ui

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.WidgetSettingsLoader
import com.example.minutewatcher.core.model.Constants
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
        suspend fun save(state: WidgetSettingsState): Boolean =
            try {
                if (state.settingsLoadFailed) {
                    AppLogger.w("Skipping save in WidgetSettingsSaver: settings were not loaded successfully")
                    return false
                }
                val validatedState = validate(state)
                loader.save(validatedState)
                dataChangeNotifier.notifyDataChanged(updateWidget = true, force = true)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Failed to save widget settings", e)
                false
            }

        /**
         * Защитная валидация перед сохранением.
         * Дублирует логику WidgetSettingsViewModel.validateSettings,
         * чтобы saver был самодостаточным и не допускал записи
         * невалидных данных при прямом вызове.
         *
         * Строгое соблюдение API: все диапазоны соответствуют
         * константам Constants.Widget.*.
         */
        private fun validate(state: WidgetSettingsState): WidgetSettingsState =
            state.copy(
                layoutMode =
                    state.layoutMode.coerceIn(
                        Constants.Widget.MIN_LAYOUT_MODE,
                        Constants.Widget.MAX_LAYOUT_MODE,
                    ),
                simArrangement =
                    state.simArrangement.coerceIn(
                        Constants.Widget.MIN_SIM_ARRANGEMENT,
                        Constants.Widget.MAX_SIM_ARRANGEMENT,
                    ),
                transparency =
                    state.transparency.coerceIn(
                        Constants.Widget.MIN_TRANSPARENCY,
                        Constants.Widget.MAX_TRANSPARENCY,
                    ),
                fontSize =
                    state.fontSize.coerceIn(
                        Constants.Widget.MIN_FONT_SIZE,
                        Constants.Widget.MAX_FONT_SIZE,
                    ),
                widgetLimitExceeded = false,
            )
    }
