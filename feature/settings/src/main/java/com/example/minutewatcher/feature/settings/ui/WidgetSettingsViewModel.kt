package com.example.minutewatcher.feature.settings.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.minutewatcher.core.android.util.WidgetUiMapper
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.WidgetSettingsLoader
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.SimSettingsState
import com.example.minutewatcher.core.model.WidgetSettingsState
import com.example.minutewatcher.core.model.WidgetUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
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

        val uiState: StateFlow<WidgetUiState?> =
            _settingsState
                .filterNotNull()
                .combine(usageStateRepository.observeSnapshot()) { state, snapshot ->
                    WidgetUiMapper.mapFromSnapshot(context, snapshot, state)
                }.stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(Constants.Flow.STATE_FLOW_STOP_TIMEOUT_MS),
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
            viewModelScope.launch {
                try {
                    _settingsState.value = loader.load()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    com.example.minutewatcher.core.common.util.AppLogger
                        .e("Failed to load widget settings", e)
                    _loadError.value = true
                }
            }
        }

        fun updateSettings(transform: (WidgetSettingsState) -> WidgetSettingsState) {
            _settingsState.value = _settingsState.value?.let(transform)
        }

        fun updateSimSettings(
            slot: Int,
            update: (SimSettingsState) -> SimSettingsState,
        ) {
            val currentState = _settingsState.value ?: return
            val currentSims = currentState.simStates.toMutableMap()
            val simState = currentSims[slot] ?: return
            currentSims[slot] = update(simState)
            _settingsState.value = currentState.copy(simStates = currentSims)
        }

        fun saveAndNotify(onComplete: (Boolean) -> Unit) {
            val currentState =
                _settingsState.value ?: run {
                    com.example.minutewatcher.core.common.util.AppLogger
                        .w("saveAndNotify called before settings were loaded")
                    onComplete(false)
                    return
                }

            viewModelScope.launch {
                val success = saver.save(currentState)
                onComplete(success)
            }
        }
    }
