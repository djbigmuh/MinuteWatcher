package com.example.minutewatcher.feature.settings.ui

import androidx.lifecycle.viewModelScope
import com.example.minutewatcher.core.common.BaseViewModel
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.WidgetSettingsLoader
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SimSettingsState
import com.example.minutewatcher.core.model.WidgetSettingsState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * UI State for the Widget Settings screen.
 */
data class WidgetSettingsUiState(
    val settings: WidgetSettingsState? = null,
    val isLoadError: Boolean = false,
    val isSaving: Boolean = false,
    val saveResult: SaveResult? = null,
)

/**
 * WidgetSettingsViewModel manages the state and persistence of widget settings.
 *
 * **Thread Safety:** Safe to call from any coroutine context.
 * **Error Handling:** Propagates [IOException] and [CancellationException].
 * Handles timeouts and concurrent access.
 * **AOSP DataStore:** Fully compliant with AOSP DataStore semantics via [WidgetSettingsLoader]
 * and [WidgetSettingsSaver].
 *
 * @property loader Loader for widget settings.
 * @property saver Saver for widget settings.
 * @param usageStateRepository Repository for observed usage state snapshots.
 */
@HiltViewModel
class WidgetSettingsViewModel
    @Inject
    constructor(
        private val loader: WidgetSettingsLoader,
        private val saver: WidgetSettingsSaver,
        usageStateRepository: UsageStateRepository,
    ) : BaseViewModel<WidgetSettingsUiState>(WidgetSettingsUiState()) {
        // saveMutex — мьютекс для предотвращения конкурентных сохранений.
        private val saveMutex = Mutex()

        // loadMutex — мьютекс для сериализации загрузок.
        private val loadMutex = Mutex()

        // loadJob — Job текущей загрузки.
        private var loadJob: Job? = null

        val snapshot: StateFlow<GlobalSnapshot> = usageStateRepository.observeSnapshot()

        init {
            loadSettings()
        }

        /**
         * Retries loading the widget settings.
         */
        fun retryLoad() {
            loadSettings()
        }

        /**
         * Загружает настройки виджета, используя паттерн сериализации через мьютекс.
         */
        private fun loadSettings() {
            viewModelScope.launch {
                loadMutex.withLock {
                    loadJob?.cancel()
                    loadJob?.join()
                    reduce { copy(isLoadError = false) }

                    loadJob =
                        launch {
                            try {
                                val loaded =
                                    withTimeoutOrNull(timeout = LOAD_TIMEOUT) {
                                        loader.load()
                                    }
                                ensureActive()
                                if (loaded != null) {
                                    reduce { copy(settings = loaded) }
                                } else {
                                    AppLogger.e("Failed to load widget settings: timeout")
                                    reduce { copy(isLoadError = true) }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                AppLogger.e("Failed to load widget settings", e)
                                reduce { copy(isLoadError = true) }
                            }
                        }
                }
            }
        }

        /**
         * Обновляет текущее состояние настроек виджета.
         */
        fun updateSettings(transform: (WidgetSettingsState) -> WidgetSettingsState) {
            reduce {
                val currentSettings = settings ?: return@reduce this
                copy(
                    isLoadError = false,
                    settings = transform(currentSettings),
                )
            }
        }

        /**
         * Updates settings for a specific SIM slot.
         */
        fun updateSimSettings(
            slot: Int,
            update: (SimSettingsState) -> SimSettingsState,
        ) {
            if (slot !in Constants.SimConstants.SUPPORTED_SLOT_INDICES) {
                AppLogger.w("updateSimSettings called with invalid slot $slot, ignoring")
                return
            }
            reduce {
                val currentSettings = settings ?: return@reduce this
                val currentSims = currentSettings.simStates.toMutableMap()
                val simState = currentSims[slot] ?: SimSettingsState()
                currentSims[slot] = update(simState)
                copy(
                    isLoadError = false,
                    settings = currentSettings.copy(simStates = currentSims),
                )
            }
        }

        /**
         * Consumes the last save result, resetting it to null.
         */
        fun consumeSaveResult() {
            reduce { copy(saveResult = null) }
        }

        /**
         * Сохраняет текущие настройки и уведомляет систему об изменениях.
         */
        suspend fun saveAndNotify() {
            if (!saveMutex.tryLock()) {
                reduce { copy(saveResult = SaveResult.AlreadyInProgress) }
                return
            }

            try {
                reduce { copy(isSaving = true) }
                val result = performSaveInternal()
                reduce { copy(saveResult = result) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Failed to save widget settings", e)
                reduce { copy(saveResult = SaveResult.Failed) }
            } finally {
                reduce { copy(isSaving = false) }
                saveMutex.unlock()
            }
        }

        @Suppress("ReturnCount")
        private fun determineSaveState(): SaveState {
            val currentState = state.value
            if (currentState.isLoadError) return SaveState.LoadFailed
            val settings = currentState.settings ?: return SaveState.NotLoaded
            if (settings.settingsLoadFailed) return SaveState.LoadFailed
            return SaveState.Ready(settings)
        }

        private suspend fun performSaveInternal(): SaveResult =
            when (val saveState = determineSaveState()) {
                is SaveState.LoadFailed -> {
                    AppLogger.w("saveAndNotify skipped: settings load failed previously")
                    SaveResult.Failed
                }

                is SaveState.NotLoaded -> {
                    AppLogger.w("saveAndNotify called before settings were loaded")
                    SaveResult.Failed
                }

                is SaveState.Ready -> {
                    executeSave(saveState.state)
                }
            }

        private suspend fun executeSave(state: WidgetSettingsState): SaveResult {
            val validatedState = WidgetSettingsState.validate(state)
            val isPartial = validatedState.simListLoadFailed
            if (isPartial) {
                AppLogger.w(
                    "saveAndNotify: SIM list was not loaded successfully. " +
                        "Saving only general settings via skipSimSettings = true.",
                )
            }

            val timeout = Constants.Flow.LONG_TIMEOUT_MS.milliseconds

            val result =
                withTimeoutOrNull(timeout) {
                    saver.save(validatedState, skipSimSettings = isPartial)
                } ?: run {
                    AppLogger.e("saveAndNotify: timeout during ${if (isPartial) "partial" else "full"} save.")
                    SaveResult.Failed
                }

            if (result is SaveResult.PartialFailure) {
                AppLogger.e("Widget settings partial save failure: ${result.message}")
            } else if (result is SaveResult.Failed) {
                AppLogger.e("Widget settings save failed")
            }

            return result
        }

        companion object {
            private val LOAD_TIMEOUT: Duration = 10.seconds
        }

        private sealed class SaveState {
            data object LoadFailed : SaveState()

            data object NotLoaded : SaveState()

            data class Ready(
                val state: WidgetSettingsState,
            ) : SaveState()
        }
    }
