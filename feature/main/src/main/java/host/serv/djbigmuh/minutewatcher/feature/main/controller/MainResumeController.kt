package host.serv.djbigmuh.minutewatcher.feature.main.controller

import androidx.lifecycle.SavedStateHandle
import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import host.serv.djbigmuh.minutewatcher.core.common.util.TimeProvider
import host.serv.djbigmuh.minutewatcher.core.model.Constants
import host.serv.djbigmuh.minutewatcher.feature.main.MainViewModel
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
 * Expected to be invoked on the Main thread during Activity/ViewModel resume.
 */
class MainResumeController
@Inject
constructor(
    private val timeProvider: TimeProvider,
    private val savedStateHandle: SavedStateHandle,
) {
    private var resumeOuterJob: Job? = null
    private var resumeRefreshJob: Job? = null

    /**
     * Handles activity resume logic, including debounce and stale data checks.
     *
     * **Debounce logic:** Prevents rapid re-runs within RESUME_DEBOUNCE_MS.
     * If monotonic clocks were reset (detected via negative time difference after reboot),
     * the debounce is considered expired and execution continues immediately.
     *
     * **Thread Safety:** Must be invoked on the Main thread (from Activity's [onResume]).
     * State persistence is backed by [SavedStateHandle].
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

        val previousOuter = resumeOuterJob
        resumeOuterJob =
            scope.launch {
                withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                    previousOuter?.cancelAndJoin()
                }
                resumeRefreshJob?.cancelAndJoin()
                resumeRefreshJob =
                    launch {
                        if (initController.initializationState.value is InitializationState.Failed) {
                            val lastRefreshElapsedBefore =
                                savedStateHandle.get<Long>(MainViewModel.KEY_LAST_REFRESH_TIME) ?: 0L
                            AppLogger.d("onActivityResume: initialization failed previously, retrying...")
                            initController.initializeApp(scope, lastRefreshElapsedBefore, onUpdateLastRefreshAt)
                        }

                        // If initialization is still in progress (including one just launched from Failed),
                        // wait for its completion with LONG_TIMEOUT_MS.
                        val state =
                            withTimeoutOrNull(Constants.Flow.LONG_TIMEOUT_MS.milliseconds) {
                                initController.initializationState.first {
                                    it is InitializationState.Completed ||
                                        it is InitializationState.Failed
                                }
                            }

                        if (state !is InitializationState.Completed) {
                            AppLogger.w(
                                "onActivityResume: initialization not complete after wait, skipping refresh",
                            )
                            return@launch
                        }

                        // Read refresh time and current time AFTER initialization is confirmed complete
                        val lastRefreshElapsed =
                            savedStateHandle.get<Long>(MainViewModel.KEY_LAST_REFRESH_TIME) ?: 0L
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
