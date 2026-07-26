package com.example.minutewatcher.feature.settings.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.minutewatcher.core.data.UpdateCoordinator
import com.example.minutewatcher.core.model.*
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.data.WidgetSettingsRepository
import com.example.minutewatcher.feature.widget.util.WidgetUiMapper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WidgetSettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: WidgetSettingsRepository,
    private val updateCoordinator: UpdateCoordinator,
    private val usageStateRepository: UsageStateRepository,
) : ViewModel() {

    private val _settingsState = MutableStateFlow<WidgetSettingsState?>(null)
    val settingsState: StateFlow<WidgetSettingsState?> = _settingsState.asStateFlow()

    val uiState: StateFlow<WidgetUiState?> = _settingsState
        .filterNotNull()
        .map { state ->
            val snapshot = usageStateRepository.observeSnapshot().value
            WidgetUiMapper.mapFromSnapshot(context, snapshot, state)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        viewModelScope.launch {
            _settingsState.value = repository.load()
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

    fun updateSimSettings(slot: Int, update: (SimSettingsState) -> SimSettingsState) {
        val currentState = _settingsState.value ?: return
        val currentSims = currentState.simStates.toMutableList()
        if (slot in currentSims.indices) {
            currentSims[slot] = update(currentSims[slot])
            _settingsState.value = currentState.copy(simStates = currentSims)
        }
    }

    fun saveAndNotify(onComplete: () -> Unit) {
        val currentState = _settingsState.value ?: return
        viewModelScope.launch {
            try {
                repository.save(currentState)
                updateCoordinator.notifyDataChanged(context)
                onComplete()
            } catch (e: Exception) {
                com.example.minutewatcher.core.model.util.AppLogger.e("Failed to save widget settings", e)
                onComplete()
            }
        }
    }
}
