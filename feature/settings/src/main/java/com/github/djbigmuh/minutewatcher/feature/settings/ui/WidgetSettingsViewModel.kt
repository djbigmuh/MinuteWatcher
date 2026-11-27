package com.github.djbigmuh.minutewatcher.feature.settings.ui

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.viewModelScope
import com.github.djbigmuh.minutewatcher.core.common.BaseViewModel
import com.github.djbigmuh.minutewatcher.core.common.di.ApplicationScope
import com.github.djbigmuh.minutewatcher.core.common.util.AppLogger
import com.github.djbigmuh.minutewatcher.core.domain.AodSummaryAvailability
import com.github.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import com.github.djbigmuh.minutewatcher.core.domain.UsageStateRepository
import com.github.djbigmuh.minutewatcher.core.domain.WidgetSettingsLoader
import com.github.djbigmuh.minutewatcher.core.model.AppSettings
import com.github.djbigmuh.minutewatcher.core.model.AppTheme
import com.github.djbigmuh.minutewatcher.core.model.Constants
import com.github.djbigmuh.minutewatcher.core.model.GlobalSnapshot
import com.github.djbigmuh.minutewatcher.core.model.PackageType
import com.github.djbigmuh.minutewatcher.core.model.SimSettingsState
import com.github.djbigmuh.minutewatcher.core.model.WidgetSettingsState
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
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
 * @param loader Loader for widget settings.
 * @param saver Saver for widget settings.
 * @param appSettingsRepository Repository for global application settings.
 * @param appScope Application scope used to complete pending saves on ViewModel clearance.
 * @param usageStateRepository Repository for observed usage state snapshots for widget preview.
 * @param aodSummaryAvailability Helper to check Always-on Display summary notification availability.
 */
@HiltViewModel
class WidgetSettingsViewModel
    @Inject
    constructor(
        private val loader: WidgetSettingsLoader,
        private val saver: WidgetSettingsSaver,
        private val appSettingsRepository: AppSettingsRepository,
        @ApplicationScope private val appScope: CoroutineScope,
        usageStateRepository: UsageStateRepository,
        private val aodSummaryAvailability: AodSummaryAvailability,
    ) : BaseViewModel<WidgetSettingsUiState>(WidgetSettingsUiState()) {
        private val autoSaveTrigger =
            MutableSharedFlow<Unit>(
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        private var autoSaveJob: Job? = null

        private val _aodSummaryAvailable = MutableStateFlow(false)
        val aodSummaryAvailable: StateFlow<Boolean> = _aodSummaryAvailable.asStateFlow()

        @Volatile
        private var lastSavedSettings: WidgetSettingsState? = null

        @Volatile
        private var lastLoadedSettings: WidgetSettingsState? = null

        private val pendingUpdates = mutableListOf<(WidgetSettingsState) -> WidgetSettingsState>()
        private val pendingUpdatesLock = Any()
        private val saveMutex = Mutex()

        private var loadJob: Job? = null

        val snapshot: StateFlow<GlobalSnapshot> = usageStateRepository.observeSnapshot()

        val aodSummaryHasContent: StateFlow<Boolean> =
            snapshot
                .map { s ->
                    s.timestamp != Constants.Data.EMPTY_TIMESTAMP &&
                        s.simSnapshots.any { sim ->
                            (sim.entities[PackageType.MINUTES]?.limit ?: 0L) > 0L ||
                                (sim.entities[PackageType.DATA]?.limit ?: 0L) > 0L
                        }
                }.distinctUntilChanged()
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(Constants.Flow.WHILE_SUBSCRIBED_STOP_TIMEOUT_MS),
                    initialValue = false,
                )

        /**
         * State flow for the current application theme.
         * Used to sync the settings screen theme with the main app theme.
         */
        val appTheme: StateFlow<AppTheme> =
            appSettingsRepository
                .observeAppSettings()
                .map { it.theme }
                .distinctUntilChanged()
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(Constants.Flow.WHILE_SUBSCRIBED_STOP_TIMEOUT_MS),
                    initialValue = AppTheme.FOLLOW_SYSTEM,
                )

        init {
            loadSettings()
            setupAutoSave()
            refreshAodSummaryAvailability()
        }

        /**
         * Persists that the notification permission prompt has been shown from widget settings.
         */
        fun setNotificationPermissionRequested() {
            updateNotificationPermissionSettings { copy(notificationPermissionRequested = true) }
        }

        /**
         * Persists the result of the notification permission request.
         */
        fun setNotificationPermissionPermanentlyDenied(denied: Boolean) {
            updateNotificationPermissionSettings {
                copy(
                    notificationPermissionRequested = true,
                    notificationPermissionPermanentlyDenied = denied,
                )
            }
        }

        private fun updateNotificationPermissionSettings(
            transform: AppSettings.() -> AppSettings,
        ) {
            viewModelScope.launch {
                try {
                    appSettingsRepository.updateAppSettings(transform)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    AppLogger.e("Failed to persist notification permission state", e)
                }
            }
        }

        /**
         * Refreshes availability status of Always-on Display summary notification.
         */
        fun refreshAodSummaryAvailability() {
            viewModelScope.launch {
                @Suppress("TooGenericExceptionCaught")
                try {
                    val available = aodSummaryAvailability.isAodSummaryAvailable()
                    _aodSummaryAvailable.value = available
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Operation must complete without propagating exception for AOD summary availability check.
                    AppLogger.e("WidgetSettingsViewModel: failed to check AOD summary availability", e)
                    _aodSummaryAvailable.value = false
                }
            }
        }

        private fun setupAutoSave() {
            autoSaveJob?.cancel()
            autoSaveJob =
                viewModelScope.launch {
                    @OptIn(FlowPreview::class)
                    autoSaveTrigger
                        .debounce(AUTO_SAVE_DEBOUNCE)
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
                    @Suppress("TooGenericExceptionCaught")
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
                        // Operation must complete without propagating exception for widget settings load.
                        AppLogger.e("Failed to load widget settings", e)
                        reduce { copy(isLoadError = true) }
                    }
                }
        }

        /**
         * Updates current widget settings state.
         */
        fun updateSettings(transform: (WidgetSettingsState) -> WidgetSettingsState) {
            applySettingsUpdate("settings", transform)
        }

        /**
         * Updates settings for a specific SIM card.
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
                            transform(existing.fold(base) { acc, t -> t(acc) })
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
                lastLoadedSettings = loaded
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

                if (finalSettings != loaded) {
                    autoSaveTrigger.tryEmit(Unit)
                }
            }
        }

        /**
         * Consumes and resets save result.
         */
        fun consumeSaveResult() {
            reduce { copy(saveResult = null) }
        }

        /**
         * Saves current settings and notifies system of changes.
         */
        suspend fun saveAndNotify() {
            saveMutex.withLock {
                @Suppress("TooGenericExceptionCaught")
                try {
                    val currentState = updateAndGet { copy(saveResult = null, isSaving = true) }
                    val settingsToSave = currentState.settings
                    val result = performSaveInternal(currentState)
                    currentCoroutineContext().ensureActive()

                    if (result is SaveResult.Success && settingsToSave != null) {
                        lastSavedSettings = settingsToSave
                    }

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
                    // Operation must complete without propagating exception for save and notify stability.
                    AppLogger.e("Failed to save widget settings", e)
                    currentCoroutineContext().ensureActive()
                    reduce { copy(saveResult = SaveResult.Failed(SaveFailureReason.GENERAL)) }
                } finally {
                    withContext(NonCancellable) {
                        @Suppress("TooGenericExceptionCaught")
                        try {
                            reduce { copy(isSaving = false) }
                        } catch (e: Exception) {
                            // Operation must complete without propagating exception for state reduction.
                            AppLogger.e("WidgetSettingsViewModel: Failed to update isSaving status", e)
                        }
                    }
                }
            }
        }

        private suspend fun performSaveInternal(currentState: WidgetSettingsUiState): SaveResult {
            val currentSettings = currentState.settings
            return when {
                currentState.isLoadError || (currentSettings?.settingsLoadFailed == true) -> {
                    AppLogger.w("saveAndNotify skipped: settings load failed previously")
                    SaveResult.Failed(SaveFailureReason.NOT_LOADED)
                }

                currentSettings == null -> {
                    AppLogger.w("saveAndNotify called before settings were loaded")
                    SaveResult.Failed(SaveFailureReason.IN_PROGRESS)
                }

                (lastSavedSettings != null) && (currentSettings == lastSavedSettings) -> {
                    AppLogger.d("saveAndNotify: no changes since last save, skipping")
                    SaveResult.Success
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
                SaveResult.Failed(SaveFailureReason.TIMEOUT)
            }
        }

        @VisibleForTesting
        internal suspend fun performEmergencySave(currentSettings: WidgetSettingsState) {
            @Suppress("TooGenericExceptionCaught")
            try {
                withTimeoutOrNull(FLUSH_TIMEOUT) {
                    saveMutex.withLock {
                        AppLogger.d("WidgetSettingsViewModel: Emergency flush in onCleared")
                        executeSave(currentSettings)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Operation must complete without propagating exception for emergency save stability.
                AppLogger.e("WidgetSettingsViewModel: Emergency save failed", e)
            }
        }

        override fun onCleared() {
            autoSaveJob?.cancel()

            val currentState = state.value
            val currentSettings = currentState.settings
            val baseline = lastSavedSettings ?: lastLoadedSettings

            if (currentSettings != null && baseline != null && currentSettings != baseline) {
                appScope.launch {
                    @Suppress("TooGenericExceptionCaught")
                    try {
                        performEmergencySave(currentSettings)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Operation must complete without propagating exception for emergency save coroutine.
                        AppLogger.e("WidgetSettingsViewModel: Emergency save background coroutine failed", e)
                    }
                }
            }
        }

        companion object {
            private val LOAD_TIMEOUT: Duration = 15.seconds
            private val FLUSH_TIMEOUT: Duration = 10.seconds
            private val AUTO_SAVE_DEBOUNCE: Duration = 200.milliseconds
            private const val MAX_PENDING_UPDATES = 10
        }
    }
