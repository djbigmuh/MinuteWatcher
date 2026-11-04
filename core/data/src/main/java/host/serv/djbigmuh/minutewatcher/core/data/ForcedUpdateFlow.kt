package host.serv.djbigmuh.minutewatcher.core.data

import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import host.serv.djbigmuh.minutewatcher.core.domain.UsageStateRepository
import host.serv.djbigmuh.minutewatcher.core.domain.WidgetUpdateResult
import host.serv.djbigmuh.minutewatcher.core.domain.WidgetUpdater
import host.serv.djbigmuh.minutewatcher.core.model.Constants
import host.serv.djbigmuh.minutewatcher.core.model.util.SnapshotUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

internal data class ForcedUpdateOutcome(
    val success: Boolean,
    val contentChangedDuringUpdate: Boolean,
)

/**
 * Handles execution of forced widget updates, takeover coordination, and outcome processing.
 */
internal class ForcedUpdateFlow(
    private val stateMutex: Mutex,
    private val currentUpdateJob: AtomicReference<Job?>,
    val takeoverUpdateJob: AtomicReference<Job?>,
    private val widgetUpdater: WidgetUpdater,
    private val usageStateRepository: UsageStateRepository,
    private val getConsecutiveUpdateFailures: () -> Int,
    private val setConsecutiveUpdateFailures: (Int) -> Unit,
    private val processFailureResult: suspend (WidgetUpdateResult.Failure) -> Unit,
    private val processSuccessResult: suspend (WidgetUpdateResult.Success, Int, Int) -> ForcedUpdateOutcome,
) {
    var consecutiveUpdateFailures: Int
        get() = getConsecutiveUpdateFailures()
        set(value) = setConsecutiveUpdateFailures(value)

    suspend fun performForcedUpdate(): ForcedUpdateOutcome {
        val suppressed =
            stateMutex.withLock {
                consecutiveUpdateFailures >= Constants.Widget.MAX_CONSECUTIVE_UPDATE_FAILURES
            }
        if (suppressed) {
            AppLogger.w(
                "UpdateCoordinator: forced widget update suppressed due to too many failures " +
                    "($consecutiveUpdateFailures)",
            )
            return ForcedUpdateOutcome(false, contentChangedDuringUpdate = false)
        }

        val oldJob = currentUpdateJob.getAndSet(null)
        if (oldJob != null) {
            takeoverUpdateJob.set(oldJob)
            oldJob.cancel()
            withContext(NonCancellable) {
                withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                    oldJob.join()
                }
            }
            takeoverUpdateJob.compareAndSet(oldJob, null)
        }

        widgetUpdater.recover()

        @Suppress("TooGenericExceptionCaught")
        val res =
            try {
                val snapshotBefore = usageStateRepository.observeSnapshot().value
                val hashBefore = SnapshotUtils.calculateContentHash(snapshotBefore)

                val result = widgetUpdater.updateAll()

                val hashAfter = SnapshotUtils.calculateContentHash(usageStateRepository.observeSnapshot().value)

                stateMutex.withLock {
                    when (result) {
                        is WidgetUpdateResult.Success -> processSuccessResult(result, hashBefore, hashAfter)
                        is WidgetUpdateResult.Failure -> {
                            processFailureResult(result)
                            ForcedUpdateOutcome(success = false, contentChangedDuringUpdate = false)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w(
                    "UpdateCoordinator: forced widget update failed with unexpected exception, " +
                        "treating as terminal failure",
                    e,
                )
                stateMutex.withLock {
                    processFailureResult(
                        WidgetUpdateResult.Failure(
                            reason = WidgetUpdateResult.FailureReason.TERMINAL,
                        ),
                    )
                }
                ForcedUpdateOutcome(false, contentChangedDuringUpdate = false)
            }
        return res
    }
}
