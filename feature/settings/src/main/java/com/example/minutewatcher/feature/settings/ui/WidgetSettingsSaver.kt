package com.example.minutewatcher.feature.settings.ui

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.WidgetSettingsLoader
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.WidgetSettingsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
         * Saves widget settings.
         *
         * **Strict API compliance:** Method performs defensive validation
         * of input state via [WidgetSettingsState.validate].
         * This guarantees data consistency on save even
         * if method is called directly.
         *
         * **Strict API compliance:** on AOSP settings saving completes in milliseconds.
         * Failure during SIM settings phase indicates DataStore degradation or seed
         * unavailability (custom firmware issue). General settings do not depend on seed
         * and are always saved.
         *
         * @param state Settings state.
         * @param skipSimSettings If true, SIM-specific settings are not
         *   saved (used when simListLoadFailed).
         * @return [SaveResult] operation result.
         */
        open suspend fun save(
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
                SaveResult.Failed("Save operation timed out")
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

            // Perform defensive validation.
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
                    SaveResult.Success
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
