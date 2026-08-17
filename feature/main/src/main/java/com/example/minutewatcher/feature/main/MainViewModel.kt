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
import kotlinx.coroutines.cancelAndJoin
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

        /**
         * Ошибка последней операции (например, сброса или обновления лимитов).
         * Является однократным событием: UI должен отобразить ошибку пользователю
         * (например, через Snackbar или Toast) и затем вызвать [dismissOperationError].
         */
        val lastOperationError = controllers.simData.lastOperationError

        /**
         * Сбрасывает последнюю ошибку операции. Должен вызываться UI после показа ошибки.
         */
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
         * Обновляет данные о SIM-картах и уведомляет подписчиков (включая виджет).
         *
         * **Семантика результатов [RefreshResult]:**
         * - [RefreshResult.Success]: Данные успешно обновлены из всех источников.
         *   [lastRefreshAt] обновляется.
         * - [RefreshResult.Timeout]: Часть данных (например, трафик) не получена в срок.
         *   Виджет показывает устаревшие данные с индикатором ошибки. [lastRefreshAt]
         *   обновляется, чтобы не спамить запросами при быстром переключении Activity.
         * - `null`: Обновление уже выполняется другой корутиной или произошло критическое
         *   исключение. [lastRefreshAt] НЕ обновляется, что форсирует повторную
         *   попытку при следующем [onActivityResume].
         *
         * @param force Если true, уведомление о смене данных отправляется безусловно,
         * даже если хеш данных не изменился (необходимо для обновления настроек).
         */
        private suspend fun refreshAndNotify(force: Boolean = false) {
            val result = controllers.refresh.refreshSimCardsInternal()
            when (result) {
                is RefreshResult.Success -> {
                    lastRefreshAt = timeProvider.elapsedRealtime()
                }

                is RefreshResult.Timeout -> {
                    // Обновляем timestamp, чтобы предотвратить немедленный повторный
                    // refresh при следующем onActivityResume. На AOSP refresh()
                    // завершается за миллисекунды; таймаут указывает на деградацию
                    // хранилища на кастомной прошивке. Повторная попытка будет
                    // выполнена при следующем onActivityResume через RESUME_REFRESH_THRESHOLD_MS.
                    lastRefreshAt = timeProvider.elapsedRealtime()
                }

                null -> {
                    // Обновление уже выполняется в другой корутине или завершилось ошибкой.
                    // lastRefreshAt НЕ обновляем: следующий onActivityResume повторит попытку.
                    // Строгое соблюдение API: на AOSP refresh() завершается за миллисекунды.
                    // null здесь означает конкуренцию или деградацию на кастомной прошивке.
                    AppLogger.w("refreshAndNotify: refresh returned null, skipping timestamp update")
                }
            }
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

            /**
             * Порог (мс) для принудительного обновления при onResume.
             * Если прошло больше этого времени с последнего обновления —
             * обновляем. 20 секунд выбрано как баланс между актуальностью
             * данных и нагрузкой на DataStore: при быстром переключении
             * между приложениями (< 20 с) повторный refresh не нужен,
             * т.к. данные не могли измениться за столь короткий интервал.
             *
             * **Строгое соблюдение API:** на AOSP DataStore отвечает
             * < 100 мс. Отрицательный elapsed (перезагрузка устройства)
             * всегда триггерит обновление. На кастомной прошивке, где
             * elapsedRealtime не монотонен, возможно ложное срабатывание.
             * Это проблема кастомной прошивки.
             */
            private const val RESUME_REFRESH_THRESHOLD_MS = 20_000L

            private const val MAX_INIT_RETRIES = 3
        }

        fun onActivityResume() {
            viewModelScope.launch {
                // cancelAndJoin() гарантирует, что предыдущая корутина полностью
                // завершена (включая обработку в finally-блоках) до начала новой.
                // Это предотвращает двойное обновление при быстром повторном
                // вызове onActivityResume.
                resumeRefreshJob?.cancelAndJoin()
                resumeRefreshJob =
                    launch {
                        // G-484: A simple synchronous flag check is insufficient because it doesn't ensure
                        // that asynchronous initialization (loading settings, auto-reset check) has actually finished.
                        // We wait for the initialization state to guarantee a stable state before refreshing.
                        val initialized =
                            withTimeoutOrNull(
                                Constants.Flow.SHORT_TIMEOUT_MS.milliseconds,
                            ) {
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

                        // Строгое соблюдение API: SystemClock.elapsedRealtime() монотонен
                        // в пределах одной загрузки устройства. Фиксация времени выполняется
                        // внутри корутины, а не в момент вызова onActivityResume,
                        // чтобы исключить искажение интервала на время ожидания инициализации.
                        val nowElapsed = timeProvider.elapsedRealtime()
                        val last = lastRefreshAt
                        val elapsed = nowElapsed - last
                        // G-484: If lastRefreshAt == 0L, it means initial refresh failed or hasn't completed yet.
                        // We force a retry in this case to ensure data is loaded as soon as possible.
                        // Отрицательный elapsed однозначно указывает на перезагрузку устройства.
                        val isStale =
                            (last == 0L) ||
                                (elapsed < 0) ||
                                (elapsed > RESUME_REFRESH_THRESHOLD_MS)

                        if (isStale) {
                            refreshAndNotify(force = false)
                        }
                    }
            }
        }
    }

/**
 * Планировщик автоматического сброса лимитов.
 *
 * **Поведение при гибели процесса:**
 * [isCheckStarted] — [AtomicBoolean], сбрасывается при гибели процесса.
 * [savedStateHandle] персистится. После гибели процесса `checkOnStart()`
 * выполнится повторно, что является **корректным** поведением:
 * проверка авто-сброса должна повториться, если процесс был убит
 * до завершения предыдущей проверки.
 *
 * **Строгое соблюдение API:** SavedStateHandle гарантированно
 * восстанавливается Android Framework при воссоздании ViewModel.
 * Атомарность [isCheckStarted] предотвращает конкуренцию при
 * нескольких вызовах в рамках одного экземпляра ViewModel.
 */
internal class AutoResetScheduler(
    private val savedStateHandle: SavedStateHandle,
    private val autoResetUseCase: AutoResetUseCase,
    private val timeProvider: TimeProvider,
) {
    /**
     * Флаг однократного запуска проверки авто-сброса.
     *
     * **Намеренно не сбрасывается при успехе:** проверка авто-сброса должна
     * выполняться ровно один раз за время жизни ViewModel. Повторный вызов
     * `checkOnStart()` после успешного завершения немедленно возвращает управление.
     *
     * Сбрасывается только при ошибке (включая CancellationException),
     * чтобы позволить повторную попытку при следующем вызове.
     */
    private val isCheckStarted = AtomicBoolean(false)

    fun setup() {
        if (!savedStateHandle.contains(KEY_AUTO_RESET_FAIL_COUNT)) {
            savedStateHandle[KEY_AUTO_RESET_FAIL_COUNT] = 0
        }
    }

    /**
     * Проверяет необходимость авто-сброса при старте.
     *
     * **Строгое соблюдение API:** используется монотонное время
     * (elapsedRealtime), а не wall-clock (System.currentTimeMillis).
     * Это гарантирует, что перевод системных часов пользователем
     * не приведёт к ложным срабатываниям или пропуску проверок.
     *
     * **Отклонение кастомной прошивки:** если elapsedRealtime возвращает
     * отрицательное значение или не монотонен — это проблема кастомной
     * прошивки. В этом случае проверка выполняется немедленно (elapsed < 0).
     *
     * **Стратегия приложения:** при отрицательном elapsed проверка
     * выполняется немедленно. Это безопаснее, чем пропуск проверки.
     */
    suspend fun checkOnStart() {
        if (!isCheckStarted.compareAndSet(false, true)) return

        val lastResetCheck = savedStateHandle.get<Long>(KEY_LAST_AUTO_RESET_CHECK)
        val failCount = savedStateHandle.get<Int>(KEY_AUTO_RESET_FAIL_COUNT) ?: 0

        // **Строгое соблюдение API:** используется монотонное время
        // (elapsedRealtime), а не wall-clock (System.currentTimeMillis).
        // Это гарантирует, что перевод системных часов пользователем
        // не приведёт к ложным срабатываниям или пропуску проверок.
        // На AOSP elapsedRealtime() монотонен в пределах одной загрузки.
        // На кастомной прошивке, где elapsedRealtime не монотонен,
        // возможно ложное срабатывание (isInitialOrReboot = true при
        // elapsed < 0). Это проблема кастомной прошивки.
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
                    // isCheckStarted НЕ сбрасывается: проверка выполнена успешно,
                    // повторный вызов checkOnStart() не требуется.
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
            // KEY_LAST_AUTO_RESET_CHECK намеренно НЕ обновляется при неудаче.
            // Это позволяет повторить попытку при следующем вызове checkOnStart.
            // effectiveFailCount инкрементируется для ограничения числа попыток.
            // После MAX_AUTO_RESET_RETRIES неудач попытки прекращаются.
            //
            // Строгое соблюдение API: на AOSP performAutoReset не бросает
            // исключений при корректном состоянии DataStore. Сбой возможен
            // только при деградации хранилища на кастомных прошивках,
            // не соответствующих документации Google.
            savedStateHandle[KEY_AUTO_RESET_FAIL_COUNT] = effectiveFailCount + 1
            // Сбрасываем флаг, чтобы позволить повторную попытку
            // при следующем вызове checkOnStart() (например, после
            // воссоздания ViewModel). Это намеренное поведение.
            isCheckStarted.set(false)
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
