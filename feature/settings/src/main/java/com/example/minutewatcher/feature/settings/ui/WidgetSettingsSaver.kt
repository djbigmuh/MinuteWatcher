package com.example.minutewatcher.feature.settings.ui

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.WidgetSettingsLoader
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.WidgetSettingsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

@Singleton
open class WidgetSettingsSaver
    @Inject
    constructor(
        private val loader: WidgetSettingsLoader,
        private val dataChangeNotifier: DataChangeNotifier,
    ) {
    private val saveMutex = Mutex()

        /**
         * Сохраняет настройки виджета.
         *
         * **Строгое соблюдение API:** Метод выполняет защитную валидацию
         * входного состояния через [WidgetSettingsState.validate].
         * Это гарантирует консистентность данных при сохранении, даже
         * если метод вызван напрямую.
         *
         * **Строгое соблюдение API:** на AOSP сохранение настроек завершается за миллисекунды.
         * Сбой на этапе SIM-настроек указывает на деградацию DataStore или недоступность
         * seed (проблема кастомной прошивки). Общие настройки не зависят от seed и
         * сохраняются всегда.
         *
         * @param state Состояние настроек.
         * @param skipSimSettings Если true, SIM-специфичные настройки не
         *   сохраняются (используется при simListLoadFailed).
         * @return [SaveResult] результат операции.
         */
        open suspend fun save(
            state: WidgetSettingsState,
            skipSimSettings: Boolean = false,
        ): SaveResult {
            if (!saveMutex.tryLock()) {
                AppLogger.d("WidgetSettingsSaver: save already in progress, returning AlreadyInProgress")
                return SaveResult.AlreadyInProgress
            }

            return try {
                performSave(state, skipSimSettings)
            } finally {
                saveMutex.unlock()
            }
        }

    private suspend fun performSave(
        state: WidgetSettingsState,
        skipSimSettings: Boolean,
    ): SaveResult {
        if (state.settingsLoadFailed) {
            AppLogger.w("Skipping save in WidgetSettingsSaver: settings were not loaded successfully")
            return SaveResult.Failed("Settings were not loaded")
        }

        // Выполняем защитную валидацию.
        val validatedState = WidgetSettingsState.validate(state)

        return try {
            saveGeneralSettings(validatedState)
            val simResult = trySaveSimSettings(validatedState, skipSimSettings)

            withContext(NonCancellable) {
                notifyDataChanged()
            }
            simResult
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("Failed to save general widget settings", e)
            SaveResult.Failed("Failed to save general settings")
        }
        }

    private suspend fun trySaveSimSettings(
        state: WidgetSettingsState,
        skipSimSettings: Boolean,
    ): SaveResult =
        try {
            val failedSlots = saveSimSettings(state, skipSimSettings)

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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("Failed to save SIM-specific widget settings", e)
            SaveResult.PartialFailure(
                "General settings saved, but SIM-specific settings failed due to an error.",
            )
        }

        private suspend fun notifyDataChanged() {
            try {
                withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                    dataChangeNotifier.notifyDataChanged(updateWidget = true, force = true)
                } ?: run {
                    AppLogger.w("WidgetSettingsSaver: widget notification timed out, skipping")
                }
            } catch (e: CancellationException) {
                AppLogger.w("WidgetSettingsSaver: widget notification cancelled, skipping", e)
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
