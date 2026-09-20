package com.example.minutewatcher.feature.main.controller

import androidx.lifecycle.SavedStateHandle
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.feature.main.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

/**
 * MainResumeController handles activity resume logic and stale data checks.
 *
 * Safe to call from any coroutine context.
 */
class MainResumeController
    @Inject
    constructor(
        private val timeProvider: TimeProvider,
        private val savedStateHandle: SavedStateHandle,
    ) {
        private var resumeRefreshJob: Job? = null

    /**
     * Handles activity resume logic, including debounce and stale data checks.
     *
     * **Debounce logic:** Prevents rapid re-runs within RESUME_DEBOUNCE_MS.
     * If monotonic clocks were reset (detected via negative time difference after reboot),
     * the debounce is considered expired and execution continues immediately.
     *
     * **Thread Safety:** Safe to call from any thread; internal logic runs in the provided scope.
     */
        fun onActivityResume(
            scope: CoroutineScope,
            initController: MainInitializationController,
            onRefreshNeeded: suspend () -> Boolean,
            onUpdateLastRefreshAt: (Long) -> Unit,
        ) {
            val now = timeProvider.elapsedRealtime()
            val last = savedStateHandle.get<Long>(KEY_LAST_RESUME_TIMESTAMP) ?: -1L

        val timeDiff = now - last
        val isDebounced = (last != -1L) && (timeDiff >= 0) && (timeDiff < RESUME_DEBOUNCE_MS)

        if (isDebounced) {
                return
            }
            savedStateHandle[KEY_LAST_RESUME_TIMESTAMP] = now

            scope.launch {
                resumeRefreshJob?.cancelAndJoin()
                resumeRefreshJob =
                    launch {
                        if (initController.initializationState.value is InitializationState.Failed) {
                            val lastRefreshElapsedBefore =
                                savedStateHandle.get<Long>(MainViewModel.KEY_LAST_REFRESH_TIME) ?: 0L
                            AppLogger.d("onActivityResume: initialization failed previously, retrying...")
                            initController.initializeApp(scope, lastRefreshElapsedBefore, onUpdateLastRefreshAt)
                        }

                        // Если инициализация ещё в процессе (включая только что запущенную из Failed),
                        // ждём её завершения с LONG_TIMEOUT_MS.
                        val state =
                            withTimeoutOrNull(Constants.Flow.LONG_TIMEOUT_MS.milliseconds) {
                                initController.initializationState.first {
                                    it is InitializationState.Completed ||
                                        it is InitializationState.Failed
                                }
                            }

                        if (state !is InitializationState.Completed) {
                            AppLogger.w("onActivityResume: initialization not complete after wait, skipping refresh")
                            return@launch
                        }

                        // Read refresh time and current time AFTER initialization is confirmed complete
                        val lastRefreshElapsed = savedStateHandle.get<Long>(MainViewModel.KEY_LAST_REFRESH_TIME) ?: 0L
                        val currentTime = timeProvider.elapsedRealtime()

                        val isStale =
                            (lastRefreshElapsed == 0L) ||
                                (lastRefreshElapsed > currentTime) ||
                                (currentTime - lastRefreshElapsed > RESUME_REFRESH_THRESHOLD_MS)

                        if (isStale) {
                            if (onRefreshNeeded()) {
                                onUpdateLastRefreshAt(timeProvider.elapsedRealtime())
                            }
                        }
                    }
            }
        }

        companion object {
            private const val RESUME_REFRESH_THRESHOLD_MS = 60_000L
            private const val RESUME_DEBOUNCE_MS = 5_000L
            private const val KEY_LAST_RESUME_TIMESTAMP = "last_resume_timestamp"
        }
    }
