package com.example.minutewatcher.feature.main

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.minutewatcher.core.common.di.IoDispatcher
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.AutoResetUseCase
import com.example.minutewatcher.core.domain.RefreshResult
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.AppTheme
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.feature.main.controller.MainViewModelControllers
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        private val controllers: MainViewModelControllers,
        private val appSettingsRepository: AppSettingsRepository,
        autoResetUseCase: AutoResetUseCase,
        private val savedStateHandle: SavedStateHandle,
        private val timeProvider: TimeProvider,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val initializationDeferred = CompletableDeferred<Unit>()
        private val isInitializationStarted = AtomicBoolean(false)
        private var resumeRefreshJob: Job? = null

        private val autoResetScheduler = AutoResetScheduler(savedStateHandle, autoResetUseCase, timeProvider)

        // Dialog States
        val showLimitDialog = controllers.dialog.showLimitDialog
        val showDayDialog = controllers.dialog.showDayDialog
        val showAdjustDialog = controllers.dialog.showAdjustDialog
        val showTrafficLimitationDialog = controllers.dialog.showTrafficLimitationDialog
        val showUsageAccessDialog = controllers.dialog.showUsageAccessDialog

        fun setLimitDialog(data: LimitDialogData?) = controllers.dialog.setLimitDialog(data)

        fun setDayDialog(data: DayDialogData?) = controllers.dialog.setDayDialog(data)

        fun setAdjustDialog(data: AdjustDialogData?) = controllers.dialog.setAdjustDialog(data)

        fun setTrafficLimitationDialog(show: Boolean) = controllers.dialog.setTrafficLimitationDialog(show)

        fun setUsageAccessDialog(show: Boolean) = controllers.dialog.setUsageAccessDialog(show)

        fun setPhonePermissionPermanentlyDenied(denied: Boolean) =
            controllers.banner.setPhonePermissionPermanentlyDenied(denied)

        fun setNotificationPermissionPermanentlyDenied(denied: Boolean) =
            controllers.banner.setNotificationPermissionPermanentlyDenied(denied)

        fun setNotificationPermissionRequested() = controllers.settings.setNotificationPermissionRequested()

        fun setPhonePermissionRequested() = controllers.settings.setPhonePermissionRequested()

        val showOnboardingDialog: StateFlow<Boolean> get() = controllers.dialog.showOnboardingDialog

        val appSettings: StateFlow<AppSettings> get() = controllers.settings.appSettings

        fun completeOnboarding() {
            controllers.dialog.setShowOnboardingDialog(show = false)
            controllers.settings.completeOnboarding()
        }

        val trackingBannerState: StateFlow<TrackingBannerState> get() = controllers.banner.trackingBannerState

        val uiState: StateFlow<SimCardListState> get() = controllers.simData.uiState

        val appTheme: StateFlow<AppTheme> get() = controllers.settings.appTheme

        init {
            autoResetScheduler.setup()
            initializeApp()
        }

        private fun initializeApp() {
            // Protection against multiple initialization calls.
            if (!isInitializationStarted.compareAndSet(false, true)) return
            viewModelScope.launch(ioDispatcher) {
                try {
                    val settings = appSettingsRepository.getAppSettings().firstWithTimeout()
                    if (settings?.onboardingCompleted == false) {
                        controllers.dialog.setShowOnboardingDialog(show = true)
                    }

                    autoResetScheduler.checkOnStart()

                    var result: RefreshResult? = null
                    var retryDelay = Constants.Flow.RETRY_DELAY_MS
                    for (attempt in 0..MAX_INIT_RETRIES) {
                        result = controllers.refresh.refreshSimCardsInternal()

                        if (result is RefreshResult.Success) break

                        if (attempt < MAX_INIT_RETRIES) {
                            val reason =
                                when (result) {
                                    is RefreshResult.Timeout -> "timeout"
                                    null -> "busy/error"
                                }
                            AppLogger.d(
                                "initializeApp: refresh failed ($reason), " +
                                    "retrying in ${retryDelay}ms (attempt ${attempt + 1}/$MAX_INIT_RETRIES)",
                            )
                            kotlinx.coroutines.delay(retryDelay.milliseconds)
                            retryDelay *= 2
                        }
                    }

                    if (result is RefreshResult.Success) {
                        if (lastRefreshAt == 0L) {
                            lastRefreshAt = timeProvider.elapsedRealtime()
                        }
                    } else {
                        val reason = if (result is RefreshResult.Timeout) "Timeout" else "Error/Busy"
                        AppLogger.e("initializeApp: refresh failed after all retries ($reason)")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Failed to initialize app in ViewModel", e)
                } finally {
                    // Signal that initialization (both successful and failed attempts) is complete.
                    initializationDeferred.complete(Unit)
                }
            }
        }

        fun setTheme(theme: AppTheme) = controllers.settings.setTheme(theme)

        fun retryAppSearch() = controllers.simData.retryAppSearch()

        fun retryCalls() = controllers.simData.retryCalls()

        fun retryShortcuts() = controllers.simData.retryShortcuts()

        fun retryTraffic() {
            controllers.simData.retryTraffic { controllers.refresh.refreshSimCardsInternal() }
        }

        fun dismissWidgetError() = controllers.settings.dismissWidgetError()

        fun dismissInaccurateCalls() = controllers.settings.dismissInaccurateCalls()

        fun refreshSimCardsNow(onComplete: () -> Unit = {}) {
            viewModelScope.launch {
                try {
                    refreshAndNotify(force = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("refreshSimCardsNow failed", e)
                } finally {
                    onComplete()
                }
            }
        }

        private suspend fun refreshAndNotify(force: Boolean = false) {
            val currentTimestamp =
                controllers
                    .simData
                    .usageStateRepository
                    .observeSnapshot()
                    .value
                    .timestamp
            val result = controllers.refresh.refreshSimCardsInternal()

            if (result == null) {
                AppLogger.d("refreshAndNotify: refresh already in progress, waiting for new snapshot")
                val newSnapshot =
                    withTimeoutOrNull(Constants.Data.SNAPSHOT_TIMEOUT_MS.milliseconds) {
                        controllers.simData.usageStateRepository
                            .observeSnapshot()
                            .first { it.timestamp > currentTimestamp }
                    }
                if (newSnapshot == null) {
                    AppLogger.w("refreshAndNotify: timed out waiting for new snapshot, forcing widget notification")
                    controllers.simData.notifyDataChanged(force = force)
                    return
                }
            }

            if (result is RefreshResult.Success || result is RefreshResult.Timeout) {
                lastRefreshAt = timeProvider.elapsedRealtime()
            }

            /*
             * force=true используется для операций, требующих немедленного визуального отклика (сброс, ручная правка).
             * force=false уведомляет систему об изменениях.
             * UpdateCoordinator в core-data берет на себя ответственность за сравнение хэшей
             * и обновление виджета только при реальном изменении данных.
             */
            controllers.simData.notifyDataChanged(force = force)
        }

        val appLanguage: StateFlow<String?> get() = controllers.settings.appLanguage

        fun setLanguage(language: String?) = controllers.settings.setLanguage(language)

        fun updateLimits(
            slot: Int,
            minutesLimit: Int,
            trafficLimitMb: Int,
            rolloverEnabled: Boolean = false,
        ) {
            controllers.simData.updateLimits(slot, minutesLimit, trafficLimitMb, rolloverEnabled) {
                refreshAndNotify()
            }
        }

        fun resetUsage(slot: Int) {
            controllers.simData.resetUsage(slot) { refreshAndNotify(force = true) }
        }

        fun toggleAutoReset(
            slot: Int,
            enabled: Boolean,
        ) {
            controllers.simData.toggleAutoReset(slot, enabled) { refreshAndNotify() }
        }

        fun updateAutoResetDay(
            slot: Int,
            day: Int,
        ) {
            controllers.simData.updateAutoResetDay(slot, day) { refreshAndNotify() }
        }

        fun adjustRemainingMinutes(
            slot: Int,
            remainingMinutes: Int,
        ) {
            controllers.simData.adjustRemainingMinutes(slot, remainingMinutes) {
                refreshAndNotify(force = true)
            }
        }

        fun adjustRemainingTraffic(
            slot: Int,
            remainingGb: Float,
        ) {
            controllers.simData.adjustRemainingTraffic(slot, remainingGb) {
                refreshAndNotify(force = true)
            }
        }

        val highlightedSlot: StateFlow<Int?> = controllers.simData.highlightedSlot

        fun highlightSlot(slot: Int) = controllers.simData.highlightSlot(slot)

        private var lastRefreshAt: Long
            get() = savedStateHandle[KEY_LAST_REFRESH_ELAPSED] ?: 0L
            set(value) {
                savedStateHandle[KEY_LAST_REFRESH_ELAPSED] = value
            }

        companion object {
            internal const val KEY_LAST_REFRESH_ELAPSED = "last_refresh_elapsed"
            private const val MAX_INIT_RETRIES = 3
        }

        fun onActivityResume(nowElapsed: Long) {
            resumeRefreshJob?.cancel()
            resumeRefreshJob =
                viewModelScope.launch {
                    // G-484: A simple synchronous flag check is insufficient because it doesn't ensure
                    // that asynchronous initialization (loading settings, auto-reset check) has actually finished.
                    // We wait for the initialization deferred to guarantee a stable state before refreshing.
                    val initialized =
                        withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                            initializationDeferred.await()
                        }

                    if (initialized == null) {
                        AppLogger.w("onActivityResume: timed out waiting for initialization, skipping resume refresh")
                        return@launch
                    }

                    val last = lastRefreshAt
                    val elapsed = nowElapsed - last
                    // G-484: If lastRefreshAt == 0L, it means initial refresh failed or hasn't completed yet.
                    // We force a retry in this case to ensure data is loaded as soon as possible.
                    // Отрицательный elapsed однозначно указывает на перезагрузку устройства.
                    val isStale = (last == 0L) || (elapsed < 0) || (elapsed > Constants.UI.RESUME_REFRESH_THRESHOLD_MS)

                    if (isStale) {
                        refreshAndNotify(force = false)
                    }
                }
        }
    }

/**
 * Вспомогательный класс для планирования автоматического сброса при старте приложения.
 * Вынесен из [MainViewModel] для инкапсуляции логики работы с интервалами и SavedStateHandle.
 */
internal class AutoResetScheduler(
    private val savedStateHandle: SavedStateHandle,
    private val autoResetUseCase: AutoResetUseCase,
    private val timeProvider: TimeProvider,
) {
    private val isCheckStarted = AtomicBoolean(false)

    fun setup() {
        if (!savedStateHandle.contains(KEY_AUTO_RESET_FAIL_COUNT)) {
            savedStateHandle[KEY_AUTO_RESET_FAIL_COUNT] = 0
        }
    }

    suspend fun checkOnStart() {
        if (!isCheckStarted.compareAndSet(false, true)) return

        val lastResetCheck = savedStateHandle.get<Long>(KEY_LAST_AUTO_RESET_CHECK)
        val failCount = savedStateHandle.get<Int>(KEY_AUTO_RESET_FAIL_COUNT) ?: 0

        val now = timeProvider.elapsedRealtime()

        val isInitialOrReboot: Boolean
        val elapsed: Long

        if (lastResetCheck == null) {
            isInitialOrReboot = true
            elapsed = 0
        } else {
            elapsed = now - lastResetCheck
            isInitialOrReboot = elapsed < 0
        }

        val effectiveFailCount =
            if (isInitialOrReboot || elapsed > FAIL_COUNT_RESET_INTERVAL_MS) 0 else failCount

        try {
            if (isInitialOrReboot || elapsed > AUTO_RESET_CHECK_INTERVAL_MS) {
                if (effectiveFailCount <= MAX_AUTO_RESET_RETRIES) {
                    autoResetUseCase.performAutoReset(shouldNotify = false)
                    savedStateHandle[KEY_LAST_AUTO_RESET_CHECK] = now
                    savedStateHandle[KEY_AUTO_RESET_FAIL_COUNT] = 0
                } else {
                    AppLogger.e("Auto-reset skipped after $effectiveFailCount consecutive failures")
                    savedStateHandle[KEY_LAST_AUTO_RESET_CHECK] = now
                }
            }
        } catch (e: CancellationException) {
            isCheckStarted.set(false)
            throw e
        } catch (e: Exception) {
            AppLogger.e("Auto-reset failed during initialization", e)
            savedStateHandle[KEY_AUTO_RESET_FAIL_COUNT] = effectiveFailCount + 1
            savedStateHandle[KEY_LAST_AUTO_RESET_CHECK] = timeProvider.elapsedRealtime()
            isCheckStarted.set(false) // G-484: Reset flag to allow retry after failure
        }
    }

    companion object {
        /** Key for storing the last auto-reset check timestamp in SavedStateHandle. */
        private const val KEY_LAST_AUTO_RESET_CHECK = "last_auto_reset_check"

        /** Key for storing the consecutive auto-reset failure count in SavedStateHandle. */
        private const val KEY_AUTO_RESET_FAIL_COUNT = "auto_reset_fail_count"

        private const val AUTO_RESET_CHECK_INTERVAL_MS = 3_600_000L // 1 hour
        private const val MAX_AUTO_RESET_RETRIES = 3
        private const val FAIL_COUNT_RESET_INTERVAL_MS = 24 * 3_600_000L // 24 hours
    }
}
