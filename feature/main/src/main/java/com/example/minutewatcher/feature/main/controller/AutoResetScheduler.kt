package com.example.minutewatcher.feature.main.controller

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.domain.AutoResetScheduleRepository
import com.example.minutewatcher.core.domain.AutoResetUseCase
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.feature.main.controller.AutoResetScheduler.Companion.MAX_AUTO_RESET_RETRIES
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

/**
 * Scheduler for automatic limit reset.
 *
 * **Persistence & Timebase:**
 * - The scheduler state (last actual attempt timestamp and failure count) is persisted in app DataStore.
 * - Timebase is wall-clock time (`timeProvider.now()`).
 * - If system clock moves backwards (negative time elapsed), it is treated as interval expiration
 *   and triggers a reset check.
 * - The failure count reset window (`FAIL_COUNT_RESET_INTERVAL_MS`) is measured from the last actual attempt.
 *   While the failure count exceeds the threshold, retry checks do not update the attempt timestamp,
 *   allowing retries to resume once the failure window expires.
 * - Repeat auto-reset check is safe because [AutoResetUseCase] is idempotent regarding reset dates.
 */
class AutoResetScheduler
    @Inject
    constructor(
        private val scheduleRepository: AutoResetScheduleRepository,
        private val autoResetUseCase: AutoResetUseCase,
        private val timeProvider: TimeProvider,
    ) {
        /**
         * Flag for single execution of auto-reset check per ViewModel lifetime.
         *
         * The flag is reset on [CancellationException] or any unexpected Exception
         * to allow retrying on next initialization.
         */
        private val isCheckStarted = AtomicBoolean(false)

        /**
         * Checks necessity of auto-reset on start.
         */
        suspend fun checkOnStart() {
            if (!isCheckStarted.compareAndSet(false, true)) return

            try {
                performCheckInternal()
            } catch (e: CancellationException) {
                isCheckStarted.set(false)
                throw e
            } catch (e: Exception) {
                AppLogger.e("Auto-reset failed during initialization", e)
                withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                    val currentFailCount = scheduleRepository.getFailureCount()
                    scheduleRepository.setFailureCount(currentFailCount + 1)
                }
                isCheckStarted.set(false)
            }
        }

        private suspend fun performCheckInternal() {
            val lastCheckMillis = scheduleRepository.getLastCheckMillis()
            val failCount = scheduleRepository.getFailureCount()
            val now = timeProvider.now()

            val elapsed = if (lastCheckMillis == null) null else now - lastCheckMillis
            val isExpired =
                (lastCheckMillis == null) ||
                    (elapsed == null) ||
                    (elapsed < 0) ||
                    (elapsed > AUTO_RESET_CHECK_INTERVAL_MS)
            val isFailureWindowExpired =
                (elapsed == null) ||
                    (elapsed < 0) ||
                    (elapsed > FAIL_COUNT_RESET_INTERVAL_MS)
            val effectiveFailCount = if (isExpired && isFailureWindowExpired) 0 else failCount

            if (isExpired) {
                executeAutoResetWithRetry(now, effectiveFailCount)
            }
        }

    /**
     * Executes auto-reset if [failCount] is within the maximum allowed retries.
     *
     * Updates [AutoResetScheduleRepository.setLastCheckMillis] only when an actual auto-reset
     * attempt is made. When [failCount] exceeds [MAX_AUTO_RESET_RETRIES], the reset is skipped
     * without updating the attempt timestamp, allowing failure window to elapse relative to
     * the last actual attempt.
     */
        private suspend fun executeAutoResetWithRetry(
            now: Long,
            failCount: Int,
        ) {
            if (failCount <= MAX_AUTO_RESET_RETRIES) {
                val allReset = autoResetUseCase.performAutoReset(shouldNotify = false)
                if (!allReset) {
                    AppLogger.w("AutoResetScheduler: Auto-reset partially failed")
                }
                scheduleRepository.setLastCheckMillis(now)
                scheduleRepository.setFailureCount(if (allReset) 0 else failCount + 1)
            } else {
                AppLogger.e("Auto-reset skipped after $failCount consecutive failures")
            }
        }

        companion object {
            private const val AUTO_RESET_CHECK_INTERVAL_MS = 3_600_000L // 1 hour
            private const val MAX_AUTO_RESET_RETRIES = 3
            private const val FAIL_COUNT_RESET_INTERVAL_MS = 24 * 3_600_000L // 24 hours
        }
    }
