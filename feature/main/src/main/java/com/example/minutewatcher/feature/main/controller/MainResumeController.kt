package com.example.minutewatcher.feature.main.controller

import androidx.lifecycle.SavedStateHandle
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.model.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * MainResumeController handles activity resume logic and stale data checks.
 *
 * Safe to call from any coroutine context.
 */
class MainResumeController(
    private val timeProvider: TimeProvider,
    private val savedStateHandle: SavedStateHandle,
) {
    private var resumeRefreshJob: Job? = null

    fun onActivityResume(
        scope: CoroutineScope,
        initController: MainInitializationController,
        lastRefreshAt: Long,
        onRefreshNeeded: suspend () -> Unit,
        onUpdateLastRefreshAt: (Long) -> Unit,
    ) {
        val now = timeProvider.elapsedRealtime()
        val last = savedStateHandle.get<Long>(KEY_LAST_RESUME_TIMESTAMP) ?: -1L
        if ((last != -1L) && (now - last < RESUME_DEBOUNCE_MS)) {
            return
        }
        savedStateHandle[KEY_LAST_RESUME_TIMESTAMP] = now

            scope.launch {
                resumeRefreshJob?.cancelAndJoin()
                resumeRefreshJob =
                    launch {
                        if (initController.initializationState.value is InitializationState.Failed) {
                            AppLogger.d("onActivityResume: initialization failed previously, retrying...")
                            initController.initializeApp(scope, lastRefreshAt, onUpdateLastRefreshAt)
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

                        val nowWall = timeProvider.now()
                        val isStale =
                            (lastRefreshAt == 0L) ||
                                (lastRefreshAt > nowWall) ||
                                (nowWall - lastRefreshAt > RESUME_REFRESH_THRESHOLD_MS)

                        if (isStale) {
                            if (lastRefreshAt > nowWall) {
                                AppLogger.d("onActivityResume: clock moved backwards, resetting lastRefreshAt")
                                onUpdateLastRefreshAt(0L)
                            }
                            onRefreshNeeded()
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
