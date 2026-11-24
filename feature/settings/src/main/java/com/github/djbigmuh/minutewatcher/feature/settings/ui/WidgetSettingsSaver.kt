package com.github.djbigmuh.minutewatcher.feature.settings.ui

import com.github.djbigmuh.minutewatcher.core.common.di.ApplicationScope
import com.github.djbigmuh.minutewatcher.core.common.di.IoDispatcher
import com.github.djbigmuh.minutewatcher.core.common.util.AppLogger
import com.github.djbigmuh.minutewatcher.core.domain.WidgetSettingsLoader
import com.github.djbigmuh.minutewatcher.core.domain.WidgetUpdater
import com.github.djbigmuh.minutewatcher.core.model.Constants
import com.github.djbigmuh.minutewatcher.core.model.WidgetSettingsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
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
        private val widgetUpdater: WidgetUpdater,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
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

        @Suppress("TooGenericExceptionCaught", "ThrowsCount")
        private suspend fun performSave(
            state: WidgetSettingsState,
            skipSimSettings: Boolean,
        ): SaveResult {
            if (state.settingsLoadFailed) {
                AppLogger.w("Skipping save in WidgetSettingsSaver: settings were not loaded successfully")
                return SaveResult.Failed(SaveFailureReason.NOT_LOADED)
            }

            val validatedState = WidgetSettingsState.validate(state)

            @Suppress("TooGenericExceptionCaught")
            return try {
                saveGeneralSettings(validatedState)
                val simResult = trySaveSimSettings(validatedState, skipSimSettings)

                scope.launch(ioDispatcher) {
                    @Suppress("TooGenericExceptionCaught")
                    try {
                        widgetUpdater.updateAll()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.e("WidgetSettingsSaver: widget update failed", e)
                    }
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
            @Suppress("TooGenericExceptionCaught")
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
