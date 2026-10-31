package host.serv.djbigmuh.minutewatcher.core.data

import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import host.serv.djbigmuh.minutewatcher.core.common.util.TimeProvider
import host.serv.djbigmuh.minutewatcher.core.common.util.firstWithTimeout
import host.serv.djbigmuh.minutewatcher.core.domain.CallSessionRepository
import host.serv.djbigmuh.minutewatcher.core.model.Constants
import host.serv.djbigmuh.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

@Singleton
internal class CallSessionCleaner
    @Inject
    constructor(
        private val callSessionRepository: CallSessionRepository,
        private val attributionResolver: CallAttributionResolver,
        private val timeProvider: TimeProvider,
    ) {
        suspend fun cleanupCounters(
            subId: Int,
            context: CallTrackingContext,
        ) {
            @Suppress("TooGenericExceptionCaught")
            try {
                callSessionRepository.clearCallSession(subId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Session cleanup failed for subId $subId", e)
                context.updateStatus(
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.DATABASE_ERROR,
                        "Session cleanup failed: ${e.message}",
                    ),
                )
                throw e
            } finally {
                attributionResolver.clearFailures(subId)
            }
        }

        suspend fun clearAllSessions() {
            callSessionRepository.clearAllCallSessions()
            attributionResolver.clearAllFailures()
        }

        suspend fun clearStaleSessions(context: CallTrackingContext): Int {
            val activeSubIds = callSessionRepository.getActiveCallSubIds()
            if (activeSubIds == null) {
                AppLogger.e("CallSessionCleaner: getActiveCallSubIds returned null")
                context.updateStatus(
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.UNKNOWN,
                        "Active call subIds read timed out or failed",
                        TrackingStatus.ErrorCode.TIMEOUT,
                    ),
                )
                return 0
            }

            var clearedCount = 0
            val now = timeProvider.now()
            val currentRealtime = timeProvider.elapsedRealtime()

            for (subId in activeSubIds) {
                if (processSubId(subId, context, now, currentRealtime)) {
                    clearedCount++
                }
            }

            return clearedCount
        }

        private suspend fun processSubId(
            subId: Int,
            context: CallTrackingContext,
            now: Long,
            currentRealtime: Long,
        ): Boolean {
            val startTime = readStartTime(subId)
            val elapsedRealtime = readElapsedRealtime(subId)
            if (startTime == null || elapsedRealtime == null) return false

            val shouldClear =
                isIncompleteRecord(startTime, elapsedRealtime) ||
                    isElapsedRealtimeInFuture(elapsedRealtime, currentRealtime) ||
                    isEpochDriftExceeded(startTime, elapsedRealtime, now, currentRealtime)

            return if (shouldClear) {
                clearSessionSafely(subId, context, getStaleCriterion(startTime, elapsedRealtime, currentRealtime))
            } else {
                AppLogger.d("CallSessionCleaner: preserving valid session for subId $subId")
                false
            }
        }

        private fun getStaleCriterion(
            startTime: Long,
            elapsedRealtime: Long,
            currentRealtime: Long,
        ): String =
            when {
                isIncompleteRecord(startTime, elapsedRealtime) -> "incomplete record"
                isElapsedRealtimeInFuture(elapsedRealtime, currentRealtime) -> "elapsedRealtime in future"
                else -> "epoch drift exceeded"
            }

        private suspend fun readStartTime(subId: Int): Long? =
            @Suppress("TooGenericExceptionCaught")
            try {
                callSessionRepository
                    .getCallStartTime(subId)
                    .firstWithTimeout(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("CallSessionCleaner: failed to read startTime for subId $subId", e)
                null
            }

        private suspend fun readElapsedRealtime(subId: Int): Long? =
            @Suppress("TooGenericExceptionCaught")
            try {
                callSessionRepository
                    .getElapsedRealtime(subId)
                    .firstWithTimeout(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("CallSessionCleaner: failed to read elapsedRealtime for subId $subId", e)
                null
            }

        private suspend fun clearSessionSafely(
            subId: Int,
            context: CallTrackingContext,
            criterion: String,
        ): Boolean =
            @Suppress("TooGenericExceptionCaught")
            try {
                callSessionRepository.clearCallSession(subId)
                attributionResolver.clearFailures(subId)
                AppLogger.d("CallSessionCleaner: cleared stale session for subId $subId due to $criterion")
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("CallSessionCleaner: failed to clear session for subId $subId", e)
                context.updateStatus(
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.DATABASE_ERROR,
                        "Failed to clear session for subId $subId: ${e.message}",
                    ),
                )
                false
            }

        /**
         * Checks if the call session record is incomplete (`startTime <= 0` or `elapsedRealtime <= 0`).
         */
        private fun isIncompleteRecord(
            startTime: Long,
            elapsedRealtime: Long,
        ): Boolean = startTime <= 0L || elapsedRealtime <= 0L

        /**
         * Checks if `elapsedRealtime` exceeds the current device `elapsedRealtime`, violating
         * the monotonicity invariant of `elapsedRealtime` within a single device boot session.
         */
        private fun isElapsedRealtimeInFuture(
            elapsedRealtime: Long,
            currentElapsedRealtime: Long,
        ): Boolean = elapsedRealtime > currentElapsedRealtime

        /**
         * Checks if the calculated device boot epoch differs from the current wall-clock epoch
         * minus `elapsedRealtime` by more than [Constants.Calls.BOOT_EPOCH_TOLERANCE_MS], indicating
         * a stale session from a previous boot session where wall-clock time shifted significantly.
         *
         * Invariant: `elapsedRealtime` is strictly monotonic within a boot session.
         */
        private fun isEpochDriftExceeded(
            startTime: Long,
            elapsedRealtime: Long,
            now: Long,
            currentElapsedRealtime: Long,
        ): Boolean {
            val currentBootEpoch = now - currentElapsedRealtime
            val sessionBootEpoch = startTime - elapsedRealtime
            return abs(currentBootEpoch - sessionBootEpoch) > Constants.Calls.BOOT_EPOCH_TOLERANCE_MS
        }
    }
