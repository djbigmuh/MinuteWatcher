package com.example.minutewatcher.feature.settings.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.minutewatcher.core.android.util.WidgetUiMapper
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.WidgetSettingsLoader
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.SimSettingsState
import com.example.minutewatcher.core.model.WidgetSettingsState
import com.example.minutewatcher.core.model.WidgetUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WidgetSettingsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val loader: WidgetSettingsLoader,
        private val saver: WidgetSettingsSaver,
        usageStateRepository: UsageStateRepository,
    ) : ViewModel() {
        private val _settingsState = MutableStateFlow<WidgetSettingsState?>(null)
        val settingsState: StateFlow<WidgetSettingsState?> = _settingsState.asStateFlow()

        private val _loadError = MutableStateFlow(false)
        val loadError: StateFlow<Boolean> = _loadError.asStateFlow()

        private val _isSaving = MutableStateFlow(false)
        val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

        private var loadJob: Job? = null

        val uiState: StateFlow<WidgetUiState?> =
            _settingsState
                .filterNotNull()
                .combine(usageStateRepository.observeSnapshot()) { state, snapshot ->
                    WidgetUiMapper.mapFromSnapshot(context, snapshot, state)
                }.stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(Constants.Flow.WHILE_SUBSCRIBED_STOP_TIMEOUT_MS),
                    null,
                )

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
                        _settingsState.value = loader.load()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.e("Failed to load widget settings", e)
                        _loadError.value = true
                    }
                }
        }

        fun updateSettings(transform: (WidgetSettingsState) -> WidgetSettingsState) {
            val current = _settingsState.value
            if (current == null) {
                AppLogger.w("WidgetSettingsViewModel: updateSettings called before settings loaded, ignoring")
                return
            }
            _settingsState.value = transform(current)
        }

        fun updateSimSettings(
            slot: Int,
            update: (SimSettingsState) -> SimSettingsState,
        ) {
            val currentState = _settingsState.value
            if (currentState == null) {
                AppLogger.w("WidgetSettingsViewModel: updateSimSettings called before settings loaded, ignoring")
                return
            }
            val currentSims = currentState.simStates.toMutableMap()
            val simState =
                currentSims[slot] ?: run {
                    AppLogger.w("updateSimSettings: slot $slot not found in simStates. Creating default.")
                    SimSettingsState()
                }
            currentSims[slot] = update(simState)
            _settingsState.value = currentState.copy(simStates = currentSims)
        }

        fun saveAndNotify(onComplete: (Boolean) -> Unit) {
            viewModelScope.launch {
                if (_isSaving.value) {
                    // Сохранение уже идёт — не запускаем второе, но и не показываем ошибку
                    onComplete(true)
                    return@launch
                }
                _isSaving.value = true
                try {
                    val currentState = _settingsState.value
                    if (currentState == null) {
                        AppLogger.w("saveAndNotify called before settings were loaded")
                        onComplete(false)
                        return@launch
                    }

                    val success = saver.save(currentState)
                    onComplete(success)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Failed to save widget settings", e)
                    onComplete(false)
                } finally {
                    _isSaving.value = false
                }
            }
        }
    }
