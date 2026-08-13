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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
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
        private val initializationState = MutableStateFlow(false)
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

        val lastOperationError = controllers.simData.lastOperationError

        fun dismissOperationError() = controllers.simData.dismissOperationError()

        val appTheme: StateFlow<AppTheme> get() = controllers.settings.appTheme

        init {
            autoResetScheduler.setup()
            initializeApp()
        }

        private fun initializeApp() {
            /**
             * Повторная инициализация допустима только если предыдущая завершилась ошибкой.
             * Строгое соблюдение API: на AOSP DataStore и SharedPreferences доступны
             * сразу после создания Application. Ошибка инициализации возможна только
             * на кастомных прошивках с повреждённым хранилищем.
             */
            if (!isInitializationStarted.compareAndSet(false, true)) return

            initializationState.value = false

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
                    // Сброс isInitializationStarted при ошибке позволяет повторить инициализацию
                    // при следующем onResume. На AOSP инициализация не падает при корректном
                    // состоянии DataStore; сбой возможен только при деградации хранилища
                    // на кастомных прошивках. Повторная попытка — единственная стратегия
                    // восстановления без вмешательства пользователя.
                    isInitializationStarted.set(false)
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Failed to initialize app in ViewModel", e)
                    isInitializationStarted.set(false)
                } finally {
                    // Signal that initialization (both successful and failed attempts) is complete.
                    initializationState.value = true
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

        /**
         * Обновляет данные и уведомляет виджет.
         * force=true используется для операций, которые изменяют отображаемые
         * на виджете данные (лимиты, настройки, сброс). Без force виджет
         * обновится только при изменении хеша данных, что может не произойти
         * при изменении только настроек отображения.
         */
        private suspend fun refreshAndNotify(force: Boolean = false) {
            val currentTimestamp =
                controllers
                    .simData
                    .usageStateRepository
                    .observeSnapshot()
                    .value
                    .timestamp

            // result == null означает одно из двух:
            //   (а) Обновление уже выполняется в другой корутине (мьютекс занят).
            //       В этом случае ожидаем появления нового snapshot.
            //   (б) Произошло исключение внутри refreshSimCardsInternal.
            //       В этом случае ожидание snapshot может завершиться таймаутом.
            // В обоих случаях, если snapshot не получен, принудительно уведомляем
            // виджет (force), чтобы он отобразил последнее доступное состояние.
            // Строгое соблюдение API: на AOSP refresh() не возвращает null
            // при корректном состоянии DataStore. Ошибка возможна только при
            // деградации хранилища на кастомных прошивках.
            val result = controllers.refresh.refreshSimCardsInternal()

            if (result == null) {
                AppLogger.d("refreshAndNotify: refresh busy or error, waiting for new snapshot")
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
                // Snapshot получен успешно — обновляем timestamp последнего обновления,
                // чтобы предотвратить избыточные повторные вызовы в onActivityResume.
                lastRefreshAt = timeProvider.elapsedRealtime()
            }

            if ((result is RefreshResult.Success) || (result is RefreshResult.Timeout)) {
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
                refreshAndNotify(force = true)
            }
        }

        fun resetUsage(slot: Int) {
            controllers.simData.resetUsage(slot) { refreshAndNotify(force = true) }
        }

        fun toggleAutoReset(
            slot: Int,
            enabled: Boolean,
        ) {
            controllers.simData.toggleAutoReset(slot, enabled) { refreshAndNotify(force = true) }
        }

        fun updateAutoResetDay(
            slot: Int,
            day: Int,
        ) {
            controllers.simData.updateAutoResetDay(slot, day) { refreshAndNotify(force = true) }
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
                    // We wait for the initialization state to guarantee a stable state before refreshing.
                    val initialized =
                        withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                            initializationState.first { it }
                        }

                    /**
                     * При первом запуске lastRefreshAt = 0, что триггерит обновление.
                     * Однако если initializeApp ещё не завершился, параллельный вызов
                     * refresh может привести к двойному обновлению. Поэтому проверяем
                     * initializationState перед выполнением refresh.
                     */
                    if (initialized == null) {
                        AppLogger.w("onActivityResume: initialization not complete, skipping refresh")
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
            /**
             * При сбое авто-сброса KEY_LAST_AUTO_RESET_CHECK не обновляется,
             * чтобы следующая проверка могла сработать немедленно.
             * Строгое соблюдение API: на AOSP performAutoReset() не бросает исключений
             * при корректном состоянии DataStore; сбой возможен только при деградации
             * хранилища на кастомных прошивках. Если кастомная прошивка не соответствует
             * документации Google — это проблема кастомной прошивки.
             */
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
