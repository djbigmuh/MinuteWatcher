package com.github.djbigmuh.minutewatcher.core.data

import androidx.annotation.VisibleForTesting
import com.github.djbigmuh.minutewatcher.core.common.util.AppLogger
import com.github.djbigmuh.minutewatcher.core.common.util.TimeProvider
import com.github.djbigmuh.minutewatcher.core.data.util.DataStoreRetryHelper
import com.github.djbigmuh.minutewatcher.core.data.util.DataStoreTimeoutException
import com.github.djbigmuh.minutewatcher.core.domain.CallSessionRepository
import com.github.djbigmuh.minutewatcher.core.domain.DataChangeNotifier
import com.github.djbigmuh.minutewatcher.core.model.Constants
import com.github.djbigmuh.minutewatcher.core.model.TrackingStatus
import com.github.djbigmuh.minutewatcher.core.model.util.UnitConversionUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

@Singleton
internal class CallEndHandler
    @Inject
    constructor(
        private val callSessionRepository: CallSessionRepository,
        private val timeProvider: TimeProvider,
        private val retryHelper: DataStoreRetryHelper,
        private val attributionResolver: CallAttributionResolver,
        private val callMutexHolder: CallMutexHolder,
        private val sessionCleaner: CallSessionCleaner,
        private val settingsHandler: CallTrackingSettingsHandler,
        private val callEndResolver: CallEndResolver,
        private val dataChangeNotifier: DataChangeNotifier,
    ) {
        suspend fun performCallEnd(
            subId: Int,
            eventElapsedRealtime: Long,
            context: CallTrackingContext,
        ) {
            var shouldRefreshStatus = false
            @Suppress("TooGenericExceptionCaught")
            try {
                if (!context.checkPermissions()) {
                    shouldRefreshStatus = true
                    dataChangeNotifier.notifyDataChanged()
                    return
                }

                callMutexHolder.mutex.withLock {
                    if ((subId == -1) && !settingsHandler.isCallTrackingEnabled()) {
                        // If tracking is disabled and subId is unknown (-1), we clear all pending sessions
                        // to ensure no orphaned sessions remain when tracking is turned back on.
                        AppLogger.d("CallTracker: tracking disabled, clearing all sessions")
                        sessionCleaner.clearAllSessions()
                        shouldRefreshStatus = true
                    } else {
                        shouldRefreshStatus =
                            executeCallEndLogic(
                                subId,
                                eventElapsedRealtime,
                                context,
                            )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                handlePerformCallEndFatalError(e, TrackingStatus.ErrorType.DATABASE_ERROR, context)
            } catch (e: SecurityException) {
                handlePerformCallEndFatalError(e, TrackingStatus.ErrorType.PERMISSION_MISSING, context)
            } catch (e: Exception) {
                shouldRefreshStatus = false // Do not update status on unknown error
                handlePerformCallEndError(subId, e, context)
            } finally {
                finalizeCallEnd(shouldRefreshStatus, context)
            }
        }

        private suspend fun handlePerformCallEndFatalError(
            e: Exception,
            errorType: TrackingStatus.ErrorType,
            context: CallTrackingContext,
        ) {
            // Force refresh before rethrowing so status is consistent
            @Suppress("TooGenericExceptionCaught")
            try {
                context.refreshStatus(true)
            } catch (e2: CancellationException) {
                throw e2
            } catch (e2: Exception) {
                // Operation must complete without propagating exception for fatal error status refresh.
                AppLogger.w("CallEndHandler: refreshStatus failed after fatal error", e2)
            }
            val code = if (e is DataStoreTimeoutException) TrackingStatus.ErrorCode.TIMEOUT else null
            context.updateStatus(TrackingStatus.Error(errorType, e.message, code = code))
            throw e
        }

        private suspend fun executeCallEndLogic(
            subId: Int,
            eventElapsedRealtime: Long,
            context: CallTrackingContext,
        ): Boolean {
            if (subId != -1) {
                return endCall(subId, eventElapsedRealtime, true, context)
            }

            val result = callEndResolver.resolveUnknownSubIdEnd()
            return handleResolveResult(result, eventElapsedRealtime, context)
        }

        private suspend fun handleResolveResult(
            result: CallEndResolver.ResolveResult,
            eventElapsedRealtime: Long,
            context: CallTrackingContext,
        ): Boolean =
            when (result) {
                is CallEndResolver.ResolveResult.Error -> {
                    context.updateStatus(result.error)
                    false
                }

                is CallEndResolver.ResolveResult.SubIds -> {
                    processResolvedSubIds(result.ids, eventElapsedRealtime, context)
                    true
                }
            }

        /**
         * Processes ending calls for a list of resolved subscription IDs.
         *
         * @throws IOException If a database or storage error occurs while ending any of the call sessions.
         * @throws SecurityException If required permissions are missing while ending any of the call sessions.
         */
        private suspend fun processResolvedSubIds(
            subIdsToEnd: List<Int>,
            eventElapsedRealtime: Long,
            context: CallTrackingContext,
        ) {
            if (subIdsToEnd.isEmpty()) return

            val notifyEach = subIdsToEnd.size == 1
            var firstStorageOrPermissionException: Exception? = null

            subIdsToEnd.forEach { id ->
                @Suppress("TooGenericExceptionCaught")
                try {
                    endCall(id, eventElapsedRealtime, notifyEach, context)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    AppLogger.e("Failed to end call for subId $id during resolution (IOException)", e)
                    if (firstStorageOrPermissionException == null) {
                        firstStorageOrPermissionException = e
                    }
                } catch (e: SecurityException) {
                    AppLogger.e("Failed to end call for subId $id during resolution (SecurityException)", e)
                    if (firstStorageOrPermissionException == null) {
                        firstStorageOrPermissionException = e
                    }
                } catch (e: Exception) {
                    // Operation must complete without propagating exception for multi-SIM call end resolution.
                    AppLogger.e("Failed to end call for subId $id during resolution", e)
                }
            }
            if (!notifyEach) {
                dataChangeNotifier.notifyDataChanged()
            }
            firstStorageOrPermissionException?.let { throw it }
        }

        private suspend fun handlePerformCallEndError(
            subId: Int,
            e: Exception,
            context: CallTrackingContext,
        ) {
            AppLogger.e("Error in onCallEnded", e)
            val error = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
            // Specific rule for CallEndHandler: do not overwrite a legitimate Active status
            // from a successful concurrent refreshStatus with a stale error from the catch block.
            // The prioritized rules in TrackingStatusMachine.transition applied via
            // CallTracker.updateStatus handle this.
            context.updateStatus(error)
            if (subId != -1) {
                @Suppress("TooGenericExceptionCaught")
                try {
                    sessionCleaner.cleanupCounters(subId, context)
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    // Operation must complete without propagating exception for session counter cleanup.
                    AppLogger.e("Cleanup failed in onCallEnded catch block", ex)
                }
            }
        }

        /**
         * Finalizes the call end process by refreshing the tracker status.
         *
         * **Cleanup invariant:** This block does not rethrow exceptions, including [CancellationException].
         * The cancellation of the outer scope should be checked by the caller.
         */
        @VisibleForTesting
        internal suspend fun finalizeCallEnd(
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
                        AppLogger.w("CallEndHandler: finalizeCallEnd timed out after ${duration}ms")
                    }
                } catch (e: Exception) {
                    // Operation must complete without propagating exception for finalize call end.
                    AppLogger.w("CallTracker: refreshStatus failed in finalizeCallEnd", e)
                }
            }
        }

        suspend fun endCall(
            subId: Int,
            eventElapsedRealtime: Long,
            notify: Boolean,
            context: CallTrackingContext,
        ): Boolean {
            if (!settingsHandler.isCallTrackingEnabled()) {
                AppLogger.d("CallTracker: tracking disabled, clearing session for subId $subId")
                sessionCleaner.cleanupCounters(subId, context)
                return true
            }

            val endData = fetchCallEndData(subId, eventElapsedRealtime)
            return processCallEndLocked(subId, endData, notify, context)
        }

        private suspend fun fetchCallEndData(
            subId: Int,
            eventElapsedRealtime: Long,
        ): CallEndData {
            val currentElapsedRealtime =
                if (eventElapsedRealtime > 0) {
                    eventElapsedRealtime
                } else {
                    timeProvider.elapsedRealtime()
                }
            return CallEndData(
                startTimeResult =
                    retryHelper.readWithRetry(
                        label = "endCall:startTime",
                    ) { callSessionRepository.getCallStartTime(subId) },
                savedElapsedRealtimeResult =
                    retryHelper.readWithRetry(
                        label = "endCall:elapsedRealtimeForDuration",
                    ) { callSessionRepository.getElapsedRealtime(subId) },
                savedIdentityResult =
                    retryHelper.readWithRetry(
                        label = "endCall:identity",
                    ) { callSessionRepository.getCallIdentity(subId) },
                savedUptimeResult =
                    retryHelper.readWithRetry(
                        label = "endCall:uptime",
                    ) { callSessionRepository.getUptime(subId) },
                currentElapsedRealtime = currentElapsedRealtime,
                currentUptime = timeProvider.uptimeMillis(),
            )
        }

        private suspend fun processCallEndLocked(
            subId: Int,
            data: CallEndData,
            notify: Boolean,
            context: CallTrackingContext,
        ): Boolean {
            if (data.startTimeResult.isFailure) {
                CallSessionFailureHandler.handleReadStartTimeFailure(
                    subId,
                    data.startTimeResult.exceptionOrNull(),
                    context,
                    sessionCleaner,
                )
                return false
            }
            val startTime = data.startTimeResult.getOrThrow()
            if (startTime == 0L) {
                AppLogger.d("CallTracker: received IDLE for subId $subId but no session was active")
                return true
            }

            val validationResult = validateCallDuration(subId, data, context)
            if (validationResult != CallEndResult.CONTINUE) {
                return handleCallEndResult(subId, validationResult, context)
            }

            val savedElapsedRealtime = data.savedElapsedRealtimeResult.getOrElse { 0L }
            val durationSeconds =
                ((data.currentElapsedRealtime - savedElapsedRealtime) / Constants.Time.MILLIS_IN_SECOND).toInt()

            val durationResult =
                processCallDurationLocked(
                    subId = subId,
                    startTime = startTime,
                    durationSeconds = durationSeconds,
                    data = data,
                    notify = notify,
                    context = context,
                )

            return handleCallEndResult(subId, durationResult, context)
        }

        private suspend fun handleCallEndResult(
            subId: Int,
            result: CallEndResult,
            context: CallTrackingContext,
        ): Boolean =
            when (result) {
                CallEndResult.CLEANUP_AND_REFRESH -> {
                    sessionCleaner.cleanupCounters(subId, context)
                    true
                }

                CallEndResult.CLEANUP_ONLY -> {
                    sessionCleaner.cleanupCounters(subId, context)
                    false
                }

                CallEndResult.REFRESH_ONLY -> true
                else -> false
            }

        /**
         * Validates the call duration and determines if cleanup or refresh is needed.
         *
         * Serves as the single source of truth for stale elapsed realtime and max call duration checks.
         * Subsequent processing in [processCallDurationLocked] relies on these checks having passed.
         *
         * @return [CallEndResult.CONTINUE] if validation passed.
         *         [CallEndResult.CLEANUP_AND_REFRESH] if session was cleaned up (no elapsedRealtime)
         *         and refresh is needed.
         *         [CallEndResult.CLEANUP_ONLY] if session was cleaned up due to stale elapsed realtime
         *         or exceeded max duration.
         */
        private fun validateCallDuration(
            subId: Int,
            data: CallEndData,
            context: CallTrackingContext,
        ): CallEndResult {
            val savedElapsedRealtime = data.savedElapsedRealtimeResult.getOrElse { 0L }
            return when {
                savedElapsedRealtime > data.currentElapsedRealtime -> {
                    val message = "Stale call session from a previous boot for subId $subId"
                    AppLogger.w("CallTracker: $message, clearing session")
                    context.updateStatus(TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, message))
                    CallEndResult.CLEANUP_ONLY
                }

                (savedElapsedRealtime > 0L) &&
                    ((data.currentElapsedRealtime - savedElapsedRealtime) > Constants.Calls.MAX_CALL_DURATION_MS) -> {
                    val message = "Call duration exceeded max limit for subId $subId"
                    AppLogger.w("CallTracker: $message, clearing session")
                    context.updateStatus(TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, message))
                    CallEndResult.CLEANUP_ONLY
                }

                savedElapsedRealtime == 0L -> {
                    AppLogger.w("CallTracker: elapsedRealtime not recorded for subId $subId, clearing session")
                    CallEndResult.CLEANUP_AND_REFRESH
                }

                else -> CallEndResult.CONTINUE
            }
        }

        /**
         * Processes the call duration and determines if cleanup or refresh is needed.
         *
         * @return [CallEndResult.CLEANUP_AND_REFRESH] if session must be cleared and status refreshed.
         *         [CallEndResult.REFRESH_ONLY] if work is done (including cleanup) and status refresh is needed.
         *         [CallEndResult.HANDLED_WITH_ERROR] if an error was set, no immediate refresh needed.
         */
        private suspend fun processCallDurationLocked(
            subId: Int,
            startTime: Long,
            durationSeconds: Int,
            data: CallEndData,
            notify: Boolean,
            context: CallTrackingContext,
        ): CallEndResult =
            when {
                data.savedUptimeResult.getOrElse { 0L }.let { (it > 0L) && (data.currentUptime < it) } -> {
                    AppLogger.w("CallTracker: negative uptime duration detected for subId $subId, ignoring")
                    CallEndResult.CLEANUP_AND_REFRESH
                }

                else -> {
                    if (data.savedIdentityResult.isFailure) {
                        AppLogger.w(
                            "CallTracker: saved session identity unavailable for subId $subId, " +
                                "resolving with active SIMs",
                            data.savedIdentityResult.exceptionOrNull(),
                        )
                    }
                    val savedIdentity = data.savedIdentityResult.getOrNull()
                    val (targetIdentity, isFallbackUsed) =
                        attributionResolver.resolveTargetIdentity(
                            subId,
                            savedIdentity,
                        )

                    if (durationSeconds <= 0) {
                        AppLogger.d(
                            "CallTracker: zero-duration or negative call for identity $targetIdentity, " +
                                "clearing session",
                        )
                        CallEndResult.CLEANUP_AND_REFRESH
                    } else if (targetIdentity != null) {
                        val durationMinutes = calculateDurationMinutes(durationSeconds)
                        val callSignature =
                            buildCallSignature(
                                startTime,
                                data.savedElapsedRealtimeResult.getOrElse { 0L },
                            )
                        settingsHandler.writeCallDuration(
                            identity = targetIdentity,
                            durationMinutes = durationMinutes,
                            isFallbackUsed = isFallbackUsed,
                            notify = notify,
                            callSignature = callSignature,
                        )
                        CallEndResult.CLEANUP_AND_REFRESH
                    } else {
                        val cleared =
                            attributionResolver.handleAttributionFailure(subId, {
                                context.updateStatus(it)
                            }, { sessionCleaner.cleanupCounters(it, context) })
                        if (cleared) CallEndResult.REFRESH_ONLY else CallEndResult.HANDLED_WITH_ERROR
                    }
                }
            }

        private fun buildCallSignature(
            startTimeMillis: Long,
            startElapsedRealtime: Long,
        ): String = "$startTimeMillis|$startElapsedRealtime"

        private fun calculateDurationMinutes(seconds: Int): Int = UnitConversionUtils.secondsToMinutesCeil(seconds)

        private enum class CallEndResult {
            CONTINUE,
            CLEANUP_AND_REFRESH,
            CLEANUP_ONLY,
            REFRESH_ONLY,
            HANDLED_WITH_ERROR,
        }

        internal data class CallEndData(
            val startTimeResult: Result<Long>,
            val savedElapsedRealtimeResult: Result<Long>,
            val savedIdentityResult: Result<String?>,
            val savedUptimeResult: Result<Long>,
            val currentElapsedRealtime: Long,
            val currentUptime: Long,
        )
    }
