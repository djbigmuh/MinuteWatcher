package com.example.minutewatcher.feature.settings.ui

import androidx.lifecycle.viewModelScope
import com.example.minutewatcher.core.common.BaseViewModel
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.WidgetSettingsLoader
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SimSettingsState
import com.example.minutewatcher.core.model.WidgetSettingsState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
@Suppress("TooManyFunctions")
class WidgetSettingsViewModel
    @Inject
    constructor(
        private val loader: WidgetSettingsLoader,
        private val saver: WidgetSettingsSaver,
        usageStateRepository: UsageStateRepository,
        @ApplicationScope private val externalScope: CoroutineScope,
    ) : BaseViewModel<WidgetSettingsUiState>(WidgetSettingsUiState()) {
    private val autoSaveTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var autoSaveJob: Job? = null
    private var lastSavedSettings: WidgetSettingsState? = null

    private val pendingUpdates = mutableListOf<(WidgetSettingsState) -> WidgetSettingsState>()
    private val pendingUpdatesMutex = Mutex()
    private val saveMutex = Mutex()

    private var loadJob: Job? = null

    val snapshot: StateFlow<GlobalSnapshot> = usageStateRepository.observeSnapshot()

    init {
        loadSettings()
        setupAutoSave()
        setupSaveResultCleanup()
    }

    @OptIn(FlowPreview::class)
    private fun setupSaveResultCleanup() {
        viewModelScope.launch {
            state
                .map { it.saveResult }
                .debounce(2000.milliseconds)
                .filter { it is SaveResult.AlreadyInProgress }
                .collect {
                    reduce { copy(saveResult = null) }
                }
        }
    }

    private fun setupAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob =
            viewModelScope.launch {
                @OptIn(FlowPreview::class)
                autoSaveTrigger
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
        loadJob?.cancel()
        loadJob =
            viewModelScope.launch {
                try {
                    val loaded =
                        withTimeoutOrNull(timeout = LOAD_TIMEOUT) {
                            loader.load()
                        }
                    ensureActive()
                    if (loaded != null) {
                        applyPendingUpdatesAndSet(loaded)
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

    /**
     * Обновляет текущее состояние настроек виджета.
     */
    fun updateSettings(transform: (WidgetSettingsState) -> WidgetSettingsState) {
        val currentSettings = state.value.settings
        if (currentSettings == null) {
            queueUpdate(transform)
            return
        }

        var changed = false
        reduce {
            val transformed = transform(currentSettings)
            val validated = WidgetSettingsState.validate(transformed)
            if (validated != currentSettings) {
                changed = true
                copy(
                    isLoadError = false,
                    settings = validated.copy(widgetLimitExceeded = false),
                )
            } else {
                this
            }
        }
        if (changed) {
            autoSaveTrigger.tryEmit(Unit)
        }
    }

    /**
     * Обновляет настройки конкретной SIM-карты.
     */
    fun updateSimSettings(
        slot: Int,
        transform: (SimSettingsState) -> SimSettingsState,
    ) {
        val currentSettings = state.value.settings
        if (currentSettings == null) {
            queueUpdate { settings ->
                val updatedSimStates = settings.simStates.toMutableMap()
                val currentSimState = updatedSimStates[slot] ?: SimSettingsState()
                updatedSimStates[slot] = transform(currentSimState)
                WidgetSettingsState.validate(settings.copy(simStates = updatedSimStates))
            }
            return
        }

        var changed = false
        reduce {
            val updatedSimStates = currentSettings.simStates.toMutableMap()
            val currentSimState = updatedSimStates[slot] ?: SimSettingsState()
            updatedSimStates[slot] = transform(currentSimState)

            val validated =
                WidgetSettingsState.validate(
                    currentSettings.copy(
                        simStates = updatedSimStates,
                    ),
                )

            if (validated != currentSettings) {
                changed = true
                copy(
                    isLoadError = false,
                    settings = validated.copy(widgetLimitExceeded = false),
                )
            } else {
                this
            }
        }
        if (changed) {
            autoSaveTrigger.tryEmit(Unit)
        }
    }

    private fun queueUpdate(transform: (WidgetSettingsState) -> WidgetSettingsState) {
        viewModelScope.launch {
            pendingUpdatesMutex.withLock {
                if (pendingUpdates.size >= MAX_PENDING_UPDATES) {
                    AppLogger.w("WidgetSettingsViewModel: pendingUpdates limit reached, dropping oldest update")
                    pendingUpdates.removeAt(0)
                }
                pendingUpdates.add(transform)
                AppLogger.d("WidgetSettingsViewModel: update queued (total: ${pendingUpdates.size})")
            }
        }
    }

    private suspend fun applyPendingUpdatesAndSet(loaded: WidgetSettingsState) {
        val finalSettings =
            pendingUpdatesMutex.withLock {
                if (pendingUpdates.isEmpty()) {
                    WidgetSettingsState.validate(loaded)
                } else {
                    AppLogger.d("WidgetSettingsViewModel: applying ${pendingUpdates.size} pending updates")
                    var current = loaded
                    pendingUpdates.forEach { transform ->
                        current = transform(current)
                    }
                    pendingUpdates.clear()
                    WidgetSettingsState.validate(current)
                }
            }

        reduce {
            copy(
                settings = finalSettings.copy(widgetLimitExceeded = false),
                isLoadError = false,
            )
        }

        if (lastSavedSettings == null) {
            lastSavedSettings = finalSettings
        }

        if (finalSettings != loaded) {
            autoSaveTrigger.tryEmit(Unit)
        }
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
        if (!saveMutex.tryLock()) {
            reduce { copy(saveResult = SaveResult.AlreadyInProgress) }
            return
        }
        try {
            val currentState = updateAndGet { copy(saveResult = null, isSaving = true) }
            val settingsToSave = currentState.settings
            val result = performSaveInternal(currentState)
            currentCoroutineContext().ensureActive()

            if (result is SaveResult.Success && settingsToSave != null) {
                lastSavedSettings = settingsToSave
            }
            reduce { copy(saveResult = result) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("Failed to save widget settings", e)
            currentCoroutineContext().ensureActive()
            reduce { copy(saveResult = SaveResult.Failed("Failed to save settings")) }
        } finally {
            withContext(NonCancellable) {
                try {
                    reduce { copy(isSaving = false) }
                } catch (e: Exception) {
                    AppLogger.e("WidgetSettingsViewModel: Failed to update isSaving status", e)
                }
            }
            saveMutex.unlock()
        }
    }

    private suspend fun performSaveInternal(currentState: WidgetSettingsUiState): SaveResult {
        // Используем настройки из currentState, захваченного при входе в режим сохранения,
        // чтобы обеспечить консистентность данных.
        val currentSettings = currentState.settings
        return when {
            currentState.isLoadError || (currentSettings?.settingsLoadFailed == true) -> {
                AppLogger.w("saveAndNotify skipped: settings load failed previously")
                SaveResult.Failed("Settings were not loaded")
            }

            currentSettings == null -> {
                AppLogger.w("saveAndNotify called before settings were loaded")
                SaveResult.Failed("Settings are still loading")
            }

            else -> {
                executeSave(currentSettings)
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
            withContext(NonCancellable) {
                withTimeoutOrNull(timeout) {
                    saver.save(validatedState, skipSimSettings = isPartial)
                }
            }

        return result ?: run {
            AppLogger.e("saveAndNotify: operation timed out after ${timeout.inWholeMilliseconds}ms")
            SaveResult.Failed("Saving timed out. Storage might be slow or unavailable.")
        }
    }

    override fun onCleared() {
        autoSaveJob?.cancel()
        val currentSettings = state.value.settings
        val lastSaved = lastSavedSettings

        if (currentSettings != null && lastSaved != null && currentSettings != lastSaved) {
            AppLogger.d("WidgetSettingsViewModel: executing emergency save onCleared")
            externalScope.launch {
                withContext(NonCancellable) {
                    try {
                        val result =
                            withTimeoutOrNull(Constants.Flow.LONG_TIMEOUT_MS.milliseconds) {
                                saver.save(currentSettings, skipSimSettings = currentSettings.simListLoadFailed)
                            }
                        if (result == null) {
                            AppLogger.w("WidgetSettingsViewModel: emergency save timed out")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.e("WidgetSettingsViewModel: emergency save failed", e)
                    }
                }
            }
        }
        super.onCleared()
    }

    companion object {
        private val LOAD_TIMEOUT: Duration = 15.seconds
        private const val MAX_PENDING_UPDATES = 100
    }
}
