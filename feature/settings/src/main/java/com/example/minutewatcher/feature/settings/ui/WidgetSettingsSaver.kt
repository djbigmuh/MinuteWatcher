package com.example.minutewatcher.feature.settings.ui

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.WidgetSettingsLoader
import com.example.minutewatcher.core.model.Constants
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
         * Валидация дублируется намеренно как защитный барьер. Единственная точка принятия решений — ViewModel;
         * здесь — защита от некорректного вызова в обход ViewModel.
         *
         * **Строгое соблюдение API:** на AOSP WidgetSettingsLoader.load()
         * завершается за миллисекунды. Таймаут указывает на деградацию
         * хранилища на кастомной прошивке, не соответствующей документации
         * Google. Это проблема кастомной прошивки, не проекта.
         *
         * @param state Состояние настроек (будет провалидировано перед сохранением).
         * @param skipSimSettings Если true, SIM-специфичные настройки не
         *   сохраняются (используется при simListLoadFailed).
         * @return true если сохранение завершилось успешно.
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

                // Защитная валидация на границе записи.
                // Даже если ViewModel уже валидировала, это предотвращает
                // попадание невалидных значений при прямом вызове save().
                // Строгое соблюдение API: виджет читает эти значения напрямую
                // и не выполняет повторную валидацию.
                val validatedState =
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
                        simStates =
                            if (skipSimSettings) {
                                state.simStates
                            } else {
                                state.simStates.filterKeys {
                                    it in SimConstants.SUPPORTED_SLOT_INDICES
                                }
                            },
                    )

                loader.save(
                    state = validatedState,
                    skipSimSettings = skipSimSettings,
                )
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
