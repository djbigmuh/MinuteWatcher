package com.example.minutewatcher.feature.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject

@HiltViewModel
class WidgetSettingsViewModel
    @Inject
    constructor(
        private val loader: WidgetSettingsLoader,
        private val saver: WidgetSettingsSaver,
        usageStateRepository: UsageStateRepository,
    ) : ViewModel() {
        private val _settingsState = MutableStateFlow<WidgetSettingsState?>(null)
        val settingsState: StateFlow<WidgetSettingsState?> = _settingsState.asStateFlow()

        private val _loadError = MutableStateFlow(false)
        val loadError: StateFlow<Boolean> = _loadError.asStateFlow()

        private val saveMutex = Mutex()
        private val _isSaving = MutableStateFlow(false)
        val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

        private var loadJob: Job? = null

        val snapshot: StateFlow<GlobalSnapshot> = usageStateRepository.observeSnapshot()

        init {
            loadSettings()
        }

        fun retryLoad() {
            loadSettings()
        }

        private fun loadSettings() {
            _loadError.value = false
            loadJob?.cancel()
            loadJob =
                viewModelScope.launch {
                    try {
                        val loaded = loader.load()
                        // Защита от записи результата отменённого job.
                        // Если job был отменён между loader.load() и этой строкой,
                        // ensureActive() бросит CancellationException.
                        ensureActive()
                        _settingsState.value = loaded
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.e("Failed to load widget settings", e)
                        _loadError.value = true
                    }
                }
        }

        fun updateSettings(transform: (WidgetSettingsState) -> WidgetSettingsState) {
            _settingsState.update { current ->
                current?.let(transform) ?: current
            }
        }

        fun updateSimSettings(
            slot: Int,
            update: (SimSettingsState) -> SimSettingsState,
        ) {
            // Защитная валидация: UI должен передавать только валидные слоты,
            // но проверяем на случай изменения UI или программной ошибки.
            if (slot !in Constants.SimConstants.SUPPORTED_SLOT_INDICES) {
                AppLogger.w("updateSimSettings called with invalid slot $slot, ignoring")
                return
            }
            _settingsState.update { currentState ->
                if (currentState == null) return@update null

                val currentSims = currentState.simStates.toMutableMap()
                val simState = currentSims[slot] ?: SimSettingsState()
                currentSims[slot] = update(simState)
                currentState.copy(simStates = currentSims)
            }
        }

        suspend fun saveAndNotify(): SaveResult {
            if (!saveMutex.tryLock()) {
                // Сохранение уже выполняется — повторный вызов отклоняется, чтобы избежать гонки.
                return SaveResult.AlreadyInProgress
            }
            _isSaving.value = true
            return try {
                val currentState = _settingsState.value
                if (currentState == null) {
                    AppLogger.w("saveAndNotify called before settings were loaded")
                    SaveResult.Failed
                } else {
                    val validatedState = validateSettings(currentState)
                    val success = saver.save(validatedState)
                    if (success) SaveResult.Success else SaveResult.Failed
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Failed to save widget settings", e)
                SaveResult.Failed
            } finally {
                _isSaving.value = false
                saveMutex.unlock()
            }
        }

        /**
         * Валидирует настройки перед сохранением.
         * Строгое соблюдение API: значения coerceIn гарантируют,
         * что виджет получит корректные параметры даже если UI
         * передал некорректные данные (например, из-за бага в слайдере).
         */
        private fun validateSettings(state: WidgetSettingsState): WidgetSettingsState =
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
            )
    }
