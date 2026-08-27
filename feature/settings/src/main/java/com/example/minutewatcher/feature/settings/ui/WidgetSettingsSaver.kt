package com.example.minutewatcher.feature.settings.ui

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.WidgetSettingsLoader
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.WidgetSettingsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

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
         * **Строгое соблюдение API:** Валидация выполняется на стороне вызывающего
         * кода (ViewModel). Saver принимает провалидированное состояние.
         * Это гарантирует консистентность данных при сохранении.
         *
         * **Строгое соблюдение API:** на AOSP сохранение настроек завершается за миллисекунды.
         * Сбой на этапе SIM-настроек указывает на деградацию DataStore или недоступность
         * seed (проблема кастомной прошивки). Общие настройки не зависят от seed и
         * сохраняются всегда.
         *
         * @param state Состояние настроек (уже провалидировано).
         * @param skipSimSettings Если true, SIM-специфичные настройки не
         *   сохраняются (используется при simListLoadFailed).
         * @return [SaveResult] результат операции.
         */
        suspend fun save(
            state: WidgetSettingsState,
            skipSimSettings: Boolean = false,
        ): SaveResult {
            if (state.settingsLoadFailed) {
                AppLogger.w("Skipping save in WidgetSettingsSaver: settings were not loaded successfully")
                return SaveResult.Failed()
            }

            // Вход уже провалидирован вызывающей стороной (WidgetSettingsViewModel).
            // Повторная валидация не требуется.
            val validatedState = state

            return withContext(NonCancellable) {
                try {
                    saveGeneralSettings(validatedState)

                    val failedSlots = saveSimSettings(validatedState, skipSimSettings)

                    val result =
                        if (failedSlots.isEmpty()) {
                            if (skipSimSettings) {
                                SaveResult.PartialSuccess(skippedSimSettings = true)
                            } else {
                                SaveResult.Success
                            }
                        } else {
                            val slotsString = failedSlots.joinToString(", ")
                            SaveResult.PartialFailure("SIM settings could not be saved for slots: $slotsString")
                        }

                    notifyDataChanged()
                    result
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Failed to save widget settings", e)
                    SaveResult.Failed()
                }
            }
        }

        private suspend fun notifyDataChanged() {
            try {
                withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                    dataChangeNotifier.notifyDataChanged(updateWidget = true, force = true)
                } ?: run {
                    AppLogger.w("WidgetSettingsSaver: widget notification timed out, skipping")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("WidgetSettingsSaver: data saved, but widget notification failed", e)
            }
        }

        private suspend fun saveGeneralSettings(state: WidgetSettingsState) {
            loader.saveGeneralSettings(state)
        }

        private suspend fun saveSimSettings(
            state: WidgetSettingsState,
            skipSimSettings: Boolean,
        ): List<Int> {
            if (skipSimSettings) return emptyList()
            return loader.saveSimSettings(state)
        }
    }
