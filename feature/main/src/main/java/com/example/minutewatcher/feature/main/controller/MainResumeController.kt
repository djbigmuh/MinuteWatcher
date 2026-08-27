package com.example.minutewatcher.feature.main.controller

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.model.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
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
    ) {
        private var resumeRefreshJob: Job? = null
    private val lastResumeTimestamp = MutableStateFlow(-1L)

        fun onActivityResume(
            scope: CoroutineScope,
            initController: MainInitializationController,
            lastRefreshAt: Long,
            onRefreshNeeded: suspend () -> Unit,
            onUpdateLastRefreshAt: (Long) -> Unit,
        ) {
            val now = timeProvider.elapsedRealtime()
            val last = lastResumeTimestamp.value
            if (last != -1L && (now - last < RESUME_DEBOUNCE_MS)) {
                return
            }
            lastResumeTimestamp.value = now

            scope.launch {
                resumeRefreshJob?.cancelAndJoin()
                resumeRefreshJob =
                    launch {
                        if (initController.initializationState.value is InitializationState.Failed) {
                            AppLogger.d("onActivityResume: initialization failed previously, retrying...")
                            initController.initializeApp(scope, lastRefreshAt, onUpdateLastRefreshAt)
                        }

                        val isCompleted =
                            withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                                initController.initializationState.first { it is InitializationState.Completed }
                            }

                        if (isCompleted == null) {
                            AppLogger.w("onActivityResume: initialization not complete, skipping refresh")
                            return@launch
                        }

                        val nowElapsed = timeProvider.elapsedRealtime()
                        val elapsed = nowElapsed - lastRefreshAt
                        val isStale =
                            (lastRefreshAt == 0L) ||
                                (elapsed < 0) ||
                                (elapsed > RESUME_REFRESH_THRESHOLD_MS)

                        if (isStale) {
                            onRefreshNeeded()
                        }
                    }
            }
        }

        companion object {
            private const val RESUME_REFRESH_THRESHOLD_MS = 20_000L
            private const val RESUME_DEBOUNCE_MS = 5_000L
        }
    }
