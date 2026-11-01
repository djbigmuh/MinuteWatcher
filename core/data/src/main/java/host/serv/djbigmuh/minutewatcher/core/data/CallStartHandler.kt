package host.serv.djbigmuh.minutewatcher.core.data

import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import host.serv.djbigmuh.minutewatcher.core.common.util.TimeProvider
import host.serv.djbigmuh.minutewatcher.core.data.util.DataStoreRetryHelper
import host.serv.djbigmuh.minutewatcher.core.data.util.DataStoreTimeoutException
import host.serv.djbigmuh.minutewatcher.core.domain.CallSessionRepository
import host.serv.djbigmuh.minutewatcher.core.model.Constants
import host.serv.djbigmuh.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * Handles the initialization and recording of call start events.
 *
 * @throws IOException If a database or storage error occurs during call start recording.
 * @throws SecurityException If required permissions are missing during call start recording.
 */
@Singleton
internal class CallStartHandler
    @Inject
    constructor(
        private val callSessionRepository: CallSessionRepository,
        private val timeProvider: TimeProvider,
        private val retryHelper: DataStoreRetryHelper,
        private val attributionResolver: CallAttributionResolver,
        private val callMutexHolder: CallMutexHolder,
        private val sessionCleaner: CallSessionCleaner,
        private val settingsHandler: CallTrackingSettingsHandler,
    ) {
        /**
         * Executes the call start process for the given subscription ID.
         *
         * @throws IOException If a database or storage error occurs during call start recording.
         * @throws SecurityException If required permissions are missing during call start recording.
         */
        suspend fun executeCallStart(
            subId: Int,
            eventElapsedRealtime: Long,
            context: CallTrackingContext,
        ) {
            var shouldRefreshStatus = false
            @Suppress("TooGenericExceptionCaught")
            try {
                if (!context.checkPermissions()) {
                    shouldRefreshStatus = true
                    return
                }

                val targetSubId = attributionResolver.resolveTargetSubId(subId)
                if (targetSubId == -1) {
                    AppLogger.w("CallTracker: could not attribute start event (unknown subId and no default SIM)")
                    shouldRefreshStatus = true
                    return
                }

                callMutexHolder.mutex.withLock {
                    if (!settingsHandler.isCallTrackingEnabled()) {
                        AppLogger.d("CallTracker: tracking disabled, ignoring OFFHOOK for subId $targetSubId")
                        sessionCleaner.cleanupCounters(targetSubId, context)
                        shouldRefreshStatus = true
                        return@withLock
                    }

                    val startData = fetchCallStartData(targetSubId, eventElapsedRealtime)
                    shouldRefreshStatus = processCallStartLocked(targetSubId, startData, context)
                }
            } catch (e: Exception) {
                if (e is CancellationException) {
                    throw e
                }
                shouldRefreshStatus = false
                when (e) {
                    is IOException -> {
                        val code = if (e is DataStoreTimeoutException) TrackingStatus.ErrorCode.TIMEOUT else null
                        context.updateStatus(
                            TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR, e.message, code = code),
                        )
                    }

                    is SecurityException -> {
                        AppLogger.e("SecurityException in onCallStarted", e)
                        context.updateStatus(
                            TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING, e.message),
                        )
                    }

                    else -> {
                        AppLogger.e("Error in onCallStarted", e)
                        val error = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
                        context.updateStatus(error)
                    }
                }
                if (e is IOException || e is SecurityException) {
                    throw e
                }
            } finally {
                finalizeCallStart(shouldRefreshStatus, context)
            }
        }

        private suspend fun fetchCallStartData(
            targetSubId: Int,
            eventElapsedRealtime: Long,
        ): CallStartData {
            val startTimeResult =
                retryHelper.readWithRetry(
                    label = "getCallStartTime",
                ) { callSessionRepository.getCallStartTime(targetSubId) }
            val elapsedRealtimeResult =
                retryHelper.readWithRetry(
                    label = "getElapsedRealtime",
                ) { callSessionRepository.getElapsedRealtime(targetSubId) }
            val savedIdentityResult =
                retryHelper.readWithRetry(
                    label = "getCallIdentity",
                ) { callSessionRepository.getCallIdentity(targetSubId) }

            val currentIdentity = attributionResolver.resolveIdentity(targetSubId)
            return CallStartData(
                startTimeResult = startTimeResult,
                elapsedRealtimeResult = elapsedRealtimeResult,
                identity = savedIdentityResult.getOrNull(),
                currentIdentity = currentIdentity,
                now = timeProvider.now(),
                elapsedRealtime =
                    if (eventElapsedRealtime > 0) {
                        eventElapsedRealtime
                    } else {
                        timeProvider.elapsedRealtime()
                    },
                uptime = timeProvider.uptimeMillis(),
            )
        }

        /**
         * Processes the call start logic.
         */
        private suspend fun processCallStartLocked(
            targetSubId: Int,
            data: CallStartData,
            context: CallTrackingContext,
        ): Boolean {
            if (data.startTimeResult.isFailure) {
                CallSessionFailureHandler.handleReadStartTimeFailure(
                    targetSubId,
                    data.startTimeResult.exceptionOrNull(),
                    context,
                    sessionCleaner,
                )
                return false
            }
            val startTime = data.startTimeResult.getOrThrow()
            if (startTime != 0L) {
                val savedElapsedRealtime = data.elapsedRealtimeResult.getOrElse { 0L }
                val isStale = validateSessionStaleness(targetSubId, savedElapsedRealtime, data.elapsedRealtime, context)
                if (!isStale) {
                    handleActiveSession(targetSubId, data)
                    return true
                }
            }

            recordCallStart(targetSubId, data)
            return true
        }

        private fun handleActiveSession(
            targetSubId: Int,
            data: CallStartData,
        ) {
            if (data.identity != null && data.identity != data.currentIdentity) {
                AppLogger.w(
                    "CallTracker: identity changed during active call for subId $targetSubId" +
                        " (saved=${data.identity}, current=${data.currentIdentity})",
                )
            } else {
                AppLogger.d("CallTracker: session already active for subId $targetSubId, ignoring start event")
            }
        }

        private suspend fun recordCallStart(
            targetSubId: Int,
            data: CallStartData,
        ) {
            if (data.currentIdentity == null) {
                AppLogger.w(
                    "CallTracker: stable SIM identity unavailable for subId $targetSubId, " +
                        "saving session with null identity",
                )
            }
            callSessionRepository.saveCallSession(
                subId = targetSubId,
                startTime = data.now,
                elapsedRealtime = data.elapsedRealtime,
                uptime = data.uptime,
                identity = data.currentIdentity,
            )
            AppLogger.d("Call start recorded for subId $targetSubId at ${data.now} (identity=${data.currentIdentity})")
        }

        private suspend fun validateSessionStaleness(
            targetSubId: Int,
            savedElapsedRealtime: Long,
            currentElapsedRealtime: Long,
            context: CallTrackingContext,
        ): Boolean {
            val isStale =
                (savedElapsedRealtime > currentElapsedRealtime) ||
                    (
                        (savedElapsedRealtime > 0) &&
                            ((currentElapsedRealtime - savedElapsedRealtime) > Constants.Calls.MAX_CALL_DURATION_MS)
                    )

            if (isStale) {
                AppLogger.w("CallTracker: clearing stale session for subId $targetSubId")
                @Suppress("TooGenericExceptionCaught")
                try {
                    sessionCleaner.cleanupCounters(targetSubId, context)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Operation must complete without propagating exception for stale session cleanup.
                    AppLogger.e("Non-fatal error clearing stale session", e)
                }
            }
            return isStale
        }

        /**
         * Finalizes the call start process by optionally refreshing the tracker status.
         *
         * Returns early if status refresh is not required.
         *
         * **Cleanup invariant:** This block does not rethrow exceptions, including [CancellationException].
         * The cancellation of the outer scope should be checked by the caller.
         */
        private suspend fun finalizeCallStart(
            shouldRefreshStatus: Boolean,
            context: CallTrackingContext,
        ) {
            if (!shouldRefreshStatus) return
            withContext(NonCancellable) {
                @Suppress("TooGenericExceptionCaught")
                try {
                    val startTime = System.currentTimeMillis()
                    val result =
                        withTimeoutOrNull(Constants.Flow.NON_CANCELLABLE_CLEANUP_TIMEOUT_MS.milliseconds) {
                            context.refreshStatus(false)
                        }
                    if (result == null) {
                        val duration = System.currentTimeMillis() - startTime
                        AppLogger.w("CallStartHandler: finalizeCallStart timed out after ${duration}ms")
                    }
                } catch (e: Exception) {
                    // Operation must complete without propagating exception for status refresh finally block.
                    AppLogger.w("CallTracker: refreshStatus failed in onCallStarted finally block", e)
                }
            }
        }
    }
