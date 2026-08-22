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
         * **Единая точка валидации (защитный барьер):**
         * Валидация выполняется через [WidgetSettingsState.validate].
         * Это гарантирует консистентность данных при сохранении, даже если
         * ViewModel передаст некорректные значения.
         *
         * Строгое соблюдение API: виджет читает эти значения напрямую
         * и не выполняет повторную валидацию. Поэтому валидация на границе
         * записи — единственный гарант корректности данных в хранилище.
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

                // Единая точка валидации на границе записи.
                val validatedState = WidgetSettingsState.validate(state)

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
