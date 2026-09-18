package com.example.minutewatcher.feature.settings.ui

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.viewModelScope
import com.example.minutewatcher.core.common.BaseViewModel
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.WidgetSettingsLoader
import com.example.minutewatcher.core.model.AppTheme
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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
        appSettingsRepository: AppSettingsRepository,
        @ApplicationScope private val appScope: CoroutineScope,
        usageStateRepository: UsageStateRepository,
    ) : BaseViewModel<WidgetSettingsUiState>(WidgetSettingsUiState()) {
        private val autoSaveTrigger =
            MutableSharedFlow<Unit>(
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        private var autoSaveJob: Job? = null

        @Volatile
        @VisibleForTesting
        internal var lastSavedSettings: WidgetSettingsState? = null

        private val pendingUpdates = mutableListOf<(WidgetSettingsState) -> WidgetSettingsState>()
        private val pendingUpdatesLock = Any()
        private val saveMutex = Mutex()

        private var loadJob: Job? = null

        val snapshot: StateFlow<GlobalSnapshot> = usageStateRepository.observeSnapshot()

        /**
         * State flow for the current application theme.
         * Used to sync the settings screen theme with the main app theme.
         */
        val appTheme: StateFlow<AppTheme> =
            appSettingsRepository
                .observeAppSettings()
                .map { it.theme }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(Constants.Flow.WHILE_SUBSCRIBED_STOP_TIMEOUT_MS),
                    initialValue = AppTheme.FOLLOW_SYSTEM,
                )

        init {
            loadSettings()
            setupAutoSave()
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
            applySettingsUpdate("settings", transform)
        }

        /**
         * Обновляет настройки конкретной SIM-карты.
         */
        fun updateSimSettings(
            slot: Int,
            transform: (SimSettingsState) -> SimSettingsState,
        ) {
            val simTransform: (WidgetSettingsState) -> WidgetSettingsState = { settings ->
                val updatedSimStates = settings.simStates.toMutableMap()
                val currentSimState = updatedSimStates[slot] ?: SimSettingsState()
                updatedSimStates[slot] = transform(currentSimState)
                settings.copy(simStates = updatedSimStates)
            }

            applySettingsUpdate("SIM", simTransform)
        }

        private fun applySettingsUpdate(
            tag: String,
            transform: (WidgetSettingsState) -> WidgetSettingsState,
        ) {
            var appliedImmediately = false
            synchronized(pendingUpdatesLock) {
                val currentSettings = state.value.settings
                if (currentSettings != null) {
                    val transformed = transform(currentSettings)
                    val validated = WidgetSettingsState.validate(transformed)
                    if (validated != currentSettings) {
                        reduce {
                            copy(
                                isLoadError = false,
                                settings = validated,
                            )
                        }
                        appliedImmediately = true
                    }
                } else {
                    if (pendingUpdates.size >= MAX_PENDING_UPDATES) {
                        AppLogger.w("WidgetSettingsViewModel: pendingUpdates limit reached, merging queue")
                        val existing = pendingUpdates.toList()
                        pendingUpdates.clear()
                        pendingUpdates.add { base ->
                            existing.fold(base) { acc, t -> t(acc) }.let { transform(it) }
                        }
                    } else {
                        pendingUpdates.add(transform)
                    }
                    AppLogger.d("WidgetSettingsViewModel: $tag update queued (total: ${pendingUpdates.size})")
                }
            }

            if (appliedImmediately) {
                autoSaveTrigger.tryEmit(Unit)
            }
        }

        private fun applyPendingUpdatesAndSet(loaded: WidgetSettingsState) {
            synchronized(pendingUpdatesLock) {
                val currentSettings = state.value.settings
                if (currentSettings != null && lastSavedSettings != null && currentSettings != lastSavedSettings) {
                    AppLogger.w(
                        "WidgetSettingsViewModel: overwriting modified settings with loaded data and pending updates!",
                    )
                }

                var merged = loaded
                if (pendingUpdates.isNotEmpty()) {
                    AppLogger.d("WidgetSettingsViewModel: Applying ${pendingUpdates.size} pending updates")
                    pendingUpdates.forEach { merged = it(merged) }
                    pendingUpdates.clear()
                }
                val finalSettings = WidgetSettingsState.validate(merged)

                reduce {
                    copy(
                        settings = finalSettings,
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
            saveMutex.withLock {
                try {
                    val currentState = updateAndGet { copy(saveResult = null, isSaving = true) }
                    val settingsToSave = currentState.settings
                    val result = performSaveInternal(currentState)
                    currentCoroutineContext().ensureActive()

                    if (result is SaveResult.Success && settingsToSave != null) {
                        lastSavedSettings = settingsToSave
                    }

                    // If SIM list failed to load, show information that settings were saved partially.
                    // We treat this as success for internal state, but notify the user via PartialSuccess.
                    val finalResult =
                        if (result is SaveResult.Success && settingsToSave?.simListLoadFailed == true) {
                            SaveResult.PartialSuccess(skippedSimSettings = true)
                        } else {
                            result
                        }

                    reduce { copy(saveResult = finalResult) }
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
                }
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
            // Cancel ongoing auto-save.
            autoSaveJob?.cancel()

            val currentState = state.value
            val currentSettings = currentState.settings
            val lastSaved = lastSavedSettings

            if (currentSettings != null && lastSaved != null && currentSettings != lastSaved) {
                // Perform emergency save in application scope to ensure changes are persisted
                // even if the ViewModel is destroyed.
                appScope.launch {
                    try {
                        withTimeoutOrNull(FLUSH_TIMEOUT) {
                            saveMutex.withLock {
                                AppLogger.d("WidgetSettingsViewModel: Emergency flush in onCleared")
                                executeSave(currentSettings)
                            }
                        }
                    } catch (e: Exception) {
                        AppLogger.e("WidgetSettingsViewModel: Emergency save failed", e)
                    }
                }
            }
            super.onCleared()
        }

        @VisibleForTesting
        internal fun onClearedForTest() {
            onCleared()
        }

        companion object {
            private val LOAD_TIMEOUT: Duration = 15.seconds
            private val FLUSH_TIMEOUT: Duration = 10.seconds
            private const val MAX_PENDING_UPDATES = 10
        }
    }
