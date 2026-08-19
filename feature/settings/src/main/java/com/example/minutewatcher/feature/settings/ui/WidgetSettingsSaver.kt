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
         * **Двойная валидация (защитный барьер):**
         * Валидация через coerceIn дублирует валидацию в ViewModel.
         * Это намеренный защитный барьер: даже если ViewModel передаст
         * некорректные значения (например, из-за бага в UI-слое или
         * прямого вызова save() в обход ViewModel), значения будут
         * приведены к допустимому диапазону.
         *
         * Строгое соблюдение API: виджет читает эти значения напрямую
         * и не выполняет повторную валидацию. Поэтому валидация на границе
         * записи — единственный гарант корректности данных в хранилище.
         *
         * **Эвристика в пределах критерия надёжности:** coerceIn молча
         * приводит значения к диапазону. Это допустимо, т.к. альтернатива
         * (бросить исключение) приведёт к потере всех настроек пользователя.
         *
         * **Строгое соблюдение API:** на AOSP сохранение настроек завершается за миллисекунды.
         * Сбой на этапе SIM-настроек указывает на деградацию DataStore или недоступность
         * seed (проблема кастомной прошивки). Общие настройки не зависят от seed и
         * сохраняются всегда.
         *
         * @param state Состояние настроек (будет провалидировано перед сохранением).
         * @param skipSimSettings Если true, SIM-специфичные настройки не
         *   сохраняются (используется при simListLoadFailed).
         * @return [SaveResult] результат операции.
         */
        suspend fun save(
            state: WidgetSettingsState,
            skipSimSettings: Boolean = false,
        ): SaveResult =
            try {
                if (state.settingsLoadFailed) {
                    AppLogger.w("Skipping save in WidgetSettingsSaver: settings were not loaded successfully")
                    return SaveResult.Failed
                }

                // Защитная валидация на границе записи.
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

                saveGeneralSettings(validatedState)
                val simsSucceeded = saveSimSettings(validatedState, skipSimSettings)

                // После успешного сохранения уведомляем систему об изменениях.
                // force = true гарантирует немедленное обновление виджета,
                // даже если данные не изменились (например, пользователь изменил
                // только настройки оформления).
                //
                // Строгое соблюдение API: на AOSP notifyDataChanged завершается
                // за миллисекунды. Сбой указывает на деградацию DataStore
                // на кастомной прошивке.
                dataChangeNotifier.notifyDataChanged(updateWidget = true, force = true)

                if (simsSucceeded) {
                    if (skipSimSettings) {
                        SaveResult.PartialSuccess(skippedSimSettings = true)
                    } else {
                        SaveResult.Success
                    }
                } else {
                    SaveResult.PartialFailure("Some SIM settings could not be saved")
                }
            } catch (e: CancellationException) {
                // Пробрасываем для кооперативной отмены корутины.
                throw e
            } catch (e: Exception) {
                AppLogger.e("Failed to save widget settings", e)
                SaveResult.Failed
            }

        private suspend fun saveGeneralSettings(state: WidgetSettingsState) {
            loader.saveGeneralSettings(state)
        }

        private suspend fun saveSimSettings(
            state: WidgetSettingsState,
            skipSimSettings: Boolean,
        ): Boolean {
            if (skipSimSettings) return true
            return loader.saveSimSettings(state)
        }
    }
