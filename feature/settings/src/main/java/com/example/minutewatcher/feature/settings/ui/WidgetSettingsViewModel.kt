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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
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
    private val autoSaveFlow = MutableSharedFlow<Long>(extraBufferCapacity = 64)
    private val autoSaveCounter = AtomicLong(0L)

        private var loadJob: Job? = null
    private var loadOuterJob: Job? = null

        val snapshot: StateFlow<GlobalSnapshot> = usageStateRepository.observeSnapshot()

        init {
            loadSettings()
            setupAutoSave()
        }

    private fun setupAutoSave() {
        viewModelScope.launch {
            @OptIn(FlowPreview::class)
            autoSaveFlow
                .debounce(400.milliseconds)
                .collect {
                    saveAndNotify()
                }
        }
        }

        /**
         * Retries loading the widget settings.
         */
        fun retryLoad() {
            loadSettings()
        }

        private fun loadSettings() {
            loadOuterJob?.cancel()
            loadOuterJob =
                viewModelScope.launch {
                    // Отменяем и ЖДЁМ завершения предыдущей загрузки
                    loadJob?.cancelAndJoin()

                    loadJob =
                        launch {
                            try {
                                val loaded =
                                    withTimeoutOrNull(timeout = LOAD_TIMEOUT) {
                                        loader.load()
                                    }
                                ensureActive()
                                if (loaded != null) {
                                    reduce { copy(settings = loaded, isLoadError = false) }
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

        /**
         * Обновляет текущее состояние настроек виджета.
         */
        fun updateSettings(transform: (WidgetSettingsState) -> WidgetSettingsState) {
            reduce {
                val currentSettings = settings ?: return@reduce this
                copy(
                    isLoadError = false,
                    settings = transform(currentSettings).copy(widgetLimitExceeded = false),
                )
            }
            autoSaveFlow.tryEmit(autoSaveCounter.incrementAndGet())
        }

        /**
         * Обновляет настройки конкретной SIM-карты.
         */
        fun updateSimSettings(
            slot: Int,
            transform: (SimSettingsState) -> SimSettingsState,
        ) {
            reduce {
                val currentSettings = settings ?: return@reduce this
                val updatedSimStates = currentSettings.simStates.toMutableMap()
                val currentSimState = updatedSimStates[slot] ?: SimSettingsState()
                updatedSimStates[slot] = transform(currentSimState)

                copy(
                    isLoadError = false,
                    settings =
                        currentSettings.copy(
                            simStates = updatedSimStates,
                            widgetLimitExceeded = false,
                        ),
                )
            }
            autoSaveFlow.tryEmit(autoSaveCounter.incrementAndGet())
        }

        /**
         * Сбрасывает результат сохранения.
         */
        fun consumeSaveResult() {
            reduce { copy(saveResult = null) }
        }

        /**
         * Сохраняет текущие настройки и уведомляет систему об изменениях.
         */
        suspend fun saveAndNotify() {
            // Проверяем, не идет ли уже сохранение
            if (state.value.isSaving) {
                AppLogger.d("saveAndNotify: already in progress (state.isSaving=true)")
                reduce { copy(saveResult = SaveResult.AlreadyInProgress) }
                return
            }

            try {
                // Атомарно обновляем состояние и захватываем актуальное значение
                val currentState = updateAndGet { copy(saveResult = null, isSaving = true) }
                val result = performSaveInternal(currentState)
                currentCoroutineContext().ensureActive()
                reduce { copy(saveResult = result) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Failed to save widget settings", e)
                currentCoroutineContext().ensureActive()
                reduce { copy(saveResult = SaveResult.Failed()) }
            } finally {
                withContext(NonCancellable) {
                    try {
                        reduce { copy(isSaving = false) }
                    } catch (e: Exception) {
                        AppLogger.e("WidgetSettingsViewModel: Failed to update isSaving status", e)
                    }
                }
            }
        }

        private suspend fun performSaveInternal(currentState: WidgetSettingsUiState): SaveResult {
            val settings = currentState.settings
            return when {
                currentState.isLoadError || (settings?.settingsLoadFailed == true) -> {
                    AppLogger.w("saveAndNotify skipped: settings load failed previously")
                    SaveResult.Failed("Settings were not loaded")
                }

                settings == null -> {
                    AppLogger.w("saveAndNotify called before settings were loaded")
                    SaveResult.Failed("Settings are still loading")
                }

                else -> {
                    executeSave(settings)
                }
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
                }

            return result ?: run {
                AppLogger.e("saveAndNotify: operation timed out after ${timeout.inWholeMilliseconds}ms")
                SaveResult.Failed("Saving timed out. Storage might be slow or unavailable.")
            }
        }

        companion object {
            private val LOAD_TIMEOUT: Duration = 15.seconds
        }
    }
