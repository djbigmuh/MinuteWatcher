package com.example.minutewatcher.feature.main.controller

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.domain.AutoResetScheduleRepository
import com.example.minutewatcher.core.domain.AutoResetUseCase
import com.example.minutewatcher.core.model.Constants
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
 * - Persistence errors when updating the failure count do not block subsequent auto-reset checks.
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
         * Concurrency gate flag that prevents simultaneous execution of multiple auto-reset checks.
         *
         * The flag is released in `finally` upon completion of each check (whether successful or failed).
         * Periodicity of repeated checks is governed by the persisted check timestamp
         * ([AUTO_RESET_CHECK_INTERVAL_MS]) and the failure count window, not by this flag.
         */
        private val isCheckStarted = AtomicBoolean(false)

        /**
         * Checks necessity of auto-reset on start.
         *
         * Prevents concurrent execution of multiple checks using [isCheckStarted]. Subsequent calls
         * after a previous check completes are allowed and will perform a new check if the interval
         * since the persisted check timestamp has expired. Concurrent calls while a check is running
         * are ignored.
         */
        suspend fun checkOnStart() {
            if (!isCheckStarted.compareAndSet(false, true)) return

            try {
                performCheckInternal()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Auto-reset failed during initialization", e)
                try {
                    withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                        val currentFailCount = scheduleRepository.getFailureCount()
                        scheduleRepository.setFailureCount(currentFailCount + 1)
                    }
                } catch (e2: CancellationException) {
                    throw e2
                } catch (e2: Exception) {
                    AppLogger.w("Failed to update failure count during auto-reset error handling", e2)
                }
            } finally {
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
