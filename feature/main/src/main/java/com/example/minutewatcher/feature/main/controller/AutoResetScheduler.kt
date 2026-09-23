package com.example.minutewatcher.feature.main.controller

import androidx.lifecycle.SavedStateHandle
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.domain.AutoResetUseCase
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Scheduler for automatic limit reset.
 *
 * **Behavior on process death:**
 * - [KEY_LAST_AUTO_RESET_CHECK] is persistent in SavedStateHandle.
 * - On process death and ViewModel restoration, checkOnStart() executes again.
 * - If less than the interval has elapsed since the last check — reset is not performed.
 * - If more has elapsed — reset is performed.
 *
 * **Strict API compliance:** SavedStateHandle is guaranteed to be restored
 * by Android Framework during ViewModel recreation. Atomicity of [isCheckStarted]
 * prevents race conditions during multiple calls within a single ViewModel instance.
 *
 * **Heuristic within reliability criteria:** on process death during
 * reset check, repeat check upon restoration may perform
 * reset twice (if first reset completed but flag was not saved).
 * This is acceptable: repeat reset is idempotent (resetting already reset data
 * does not alter data).
 */
class AutoResetScheduler
    @Inject
    constructor(
        private val savedStateHandle: SavedStateHandle,
        private val autoResetUseCase: AutoResetUseCase,
        private val timeProvider: TimeProvider,
    ) {
        /**
         * Flag for single execution of auto-reset check.
         *
         * **Intentionally not reset on success:** auto-reset check
         * must run exactly once during ViewModel lifetime. Repeated calls to
         * `checkOnStart()` after completion immediately return.
         *
         * The flag is reset on [CancellationException] or any unexpected Exception
         * to allow retrying on next initialization, as in those cases
         * the check was not completed (SavedStateHandle entries not updated).
         * On partial failure (`performAutoReset` returned `false`), the flag is NOT reset,
         * because retry logic inside `executeAutoResetWithRetry` already accounted for the failure.
         */
        private val isCheckStarted = AtomicBoolean(false)

        fun setup() {
            if (!savedStateHandle.contains(KEY_AUTO_RESET_FAIL_COUNT)) {
                savedStateHandle[KEY_AUTO_RESET_FAIL_COUNT] = 0
            }
        }

        /**
         * Checks necessity of auto-reset on start.
         *
         * **Strict API compliance:** monotonic time is used
         * (elapsedRealtime), rather than wall-clock (System.currentTimeMillis).
         * This guarantees that changing system clock does not lead
         * to false triggers or missed checks.
         *
         * **Behavior on reboot:** `elapsedRealtime()` is monotonic only within
         * a single device boot and resets to zero on reboot.
         * If saved `lastResetCheck` is greater than current `now` (negative difference),
         * it means device was rebooted since last check.
         * In this case check executes immediately to ensure data integrity.
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
                val currentFailCount = savedStateHandle.get<Int>(KEY_AUTO_RESET_FAIL_COUNT) ?: 0
                savedStateHandle[KEY_AUTO_RESET_FAIL_COUNT] = currentFailCount + 1
                isCheckStarted.set(false)
            }
        }

        private suspend fun performCheckInternal() {
            val lastResetCheck = savedStateHandle.get<Long>(KEY_LAST_AUTO_RESET_CHECK)
            val failCount = savedStateHandle.get<Int>(KEY_AUTO_RESET_FAIL_COUNT) ?: 0
            val now = timeProvider.elapsedRealtime()

            val elapsed = if (lastResetCheck == null) 0L else (now - lastResetCheck)
            val isInitialOrReboot = (lastResetCheck == null) || (elapsed < 0)

            val effectiveFailCount =
                if (isInitialOrReboot || (elapsed > FAIL_COUNT_RESET_INTERVAL_MS)) 0 else failCount

            if (isInitialOrReboot || (elapsed > AUTO_RESET_CHECK_INTERVAL_MS)) {
                executeAutoResetWithRetry(now, effectiveFailCount)
            }
        }

        private suspend fun executeAutoResetWithRetry(
            now: Long,
            failCount: Int,
        ) {
            if (failCount <= MAX_AUTO_RESET_RETRIES) {
                val allReset = autoResetUseCase.performAutoReset(shouldNotify = false)
                if (!allReset) {
                    AppLogger.w("AutoResetScheduler: Auto-reset partially failed")
                }
                savedStateHandle[KEY_LAST_AUTO_RESET_CHECK] = now
                savedStateHandle[KEY_AUTO_RESET_FAIL_COUNT] = if (allReset) 0 else (failCount + 1)
            } else {
                AppLogger.e("Auto-reset skipped after $failCount consecutive failures")
                savedStateHandle[KEY_LAST_AUTO_RESET_CHECK] = now
            }
        }

        companion object {
            private const val KEY_LAST_AUTO_RESET_CHECK = "last_auto_reset_check"
            private const val KEY_AUTO_RESET_FAIL_COUNT = "auto_reset_fail_count"
            private const val AUTO_RESET_CHECK_INTERVAL_MS = 3_600_000L // 1 hour
            private const val MAX_AUTO_RESET_RETRIES = 3
            private const val FAIL_COUNT_RESET_INTERVAL_MS = 24 * 3_600_000L // 24 hours
        }
    }
