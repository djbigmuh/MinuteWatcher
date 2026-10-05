package com.example.minutewatcher.feature.settings.ui

import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.WidgetSettingsLoader
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.WidgetSettingsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        private val saveMutex = Mutex()

        /**
         * Saves widget settings.
         *
         * @param state Settings state.
         * @param skipSimSettings If true, SIM-specific settings are not saved.
         * @return [SaveResult] operation result.
         */
        suspend fun save(
            state: WidgetSettingsState,
            skipSimSettings: Boolean = false,
        ): SaveResult {
            val timeout = Constants.Flow.LONG_TIMEOUT_MS.milliseconds
            val result =
                withTimeoutOrNull(timeout) {
                    saveMutex.withLock {
                        performSave(state, skipSimSettings)
                    }
                }

            return result ?: run {
                AppLogger.e("WidgetSettingsSaver: save timed out (lock acquisition or execution exceeded budget)")
                SaveResult.Failed(SaveFailureReason.TIMEOUT)
            }
        }

        private suspend fun performSave(
            state: WidgetSettingsState,
            skipSimSettings: Boolean,
        ): SaveResult {
            if (state.settingsLoadFailed) {
                AppLogger.w("Skipping save in WidgetSettingsSaver: settings were not loaded successfully")
                return SaveResult.Failed(SaveFailureReason.NOT_LOADED)
            }

            val validatedState = WidgetSettingsState.validate(state)

            return try {
                saveGeneralSettings(validatedState)
                val simResult = trySaveSimSettings(validatedState, skipSimSettings)

                scope.launch {
                    notifyDataChanged()
                }
                simResult
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Failed to save general widget settings", e)
                SaveResult.Failed(SaveFailureReason.GENERAL)
            }
        }

        private suspend fun trySaveSimSettings(
            state: WidgetSettingsState,
            skipSimSettings: Boolean,
        ): SaveResult =
            try {
                val failedSlots = saveSimSettings(state, skipSimSettings)

                if (failedSlots.isEmpty()) {
                    SaveResult.Success
                } else {
                    AppLogger.w("SIM settings could not be saved for slots: ${failedSlots.joinToString(", ")}")
                    SaveResult.PartialFailure(failedSlots)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Failed to save SIM-specific widget settings", e)
                SaveResult.PartialFailure(emptyList())
            }

        private suspend fun notifyDataChanged() {
            try {
                withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                    dataChangeNotifier.notifyDataChanged(updateWidget = true, force = true)
                } ?: run {
                    AppLogger.w("WidgetSettingsSaver: widget notification timed out, skipping")
                }
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
