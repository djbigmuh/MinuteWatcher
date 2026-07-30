package com.example.minutewatcher.feature.settings.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.WidgetSettingsLoader
import com.example.minutewatcher.core.model.SimSettingsState
import com.example.minutewatcher.core.model.WidgetSettingsState
import com.example.minutewatcher.core.model.WidgetUiState
import com.example.minutewatcher.core.android.util.WidgetUiMapper
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
        private val dataChangeNotifier: DataChangeNotifier,
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
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

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

        fun updateTransparency(progress: Int) {
            _settingsState.value = _settingsState.value?.copy(transparency = progress)
        }

        fun updateFontSize(progress: Int) {
            _settingsState.value = _settingsState.value?.copy(fontSize = progress)
        }

        fun updateBackgroundColor(color: Int) {
            _settingsState.value = _settingsState.value?.copy(backgroundColor = color)
        }

        fun updateLayoutMode(mode: Int) {
            _settingsState.value = _settingsState.value?.copy(layoutMode = mode)
        }

        fun updateSimArrangement(arrangement: Int) {
            _settingsState.value = _settingsState.value?.copy(simArrangement = arrangement)
        }

        fun updateNotifyMinutes(enabled: Boolean) {
            _settingsState.value = _settingsState.value?.copy(notifyLowMinutes = enabled)
        }

        fun updateNotifyTraffic(enabled: Boolean) {
            _settingsState.value = _settingsState.value?.copy(notifyLowTraffic = enabled)
        }

        fun updateCallTracking(enabled: Boolean) {
            _settingsState.value = _settingsState.value?.copy(callTrackingEnabled = enabled)
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
                    onComplete(false)
                    return
                }

            viewModelScope.launch {
                try {
                    loader.save(currentState)
                    dataChangeNotifier.notifyDataChanged()
                    onComplete(true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    com.example.minutewatcher.core.common.util.AppLogger
                        .e("Failed to save widget settings", e)
                    onComplete(false)
                }
            }
        }
    }

