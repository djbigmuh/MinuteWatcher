package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.data.util.DataStoreRetryHelper
import com.example.minutewatcher.core.domain.CallSessionRepository
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
// Suppressing TooManyFunctions as this handler manages the complex end-of-call logic
// including multi-SIM resolution, duration calculation, and state cleanup.
@Suppress("TooManyFunctions")
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
        @Suppress("LongMethod", "ThrowsCount")
        suspend fun performCallEnd(
            subId: Int,
            context: CallTrackingContext,
        ) {
            var shouldRefreshStatus = false
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
                                context,
                            )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                shouldRefreshStatus = false
                // Force refresh before rethrowing so status is consistent
                try {
                    context.refreshStatus(true)
                } catch (_: Exception) {
                }
                context.updateStatus(TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR, e.message))
                throw e
            } catch (e: SecurityException) {
                shouldRefreshStatus = false
                AppLogger.e("SecurityException in onCallEnded", e)
                // Force refresh before rethrowing so status is consistent
                try {
                    context.refreshStatus(true)
                } catch (_: Exception) {
                }
                context.updateStatus(TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING, e.message))
                throw e
            } catch (e: Exception) {
                shouldRefreshStatus = false // Не обновляем статус при неизвестной ошибке
                handlePerformCallEndError(subId, e, context)
            } finally {
                finalizeCallEnd(shouldRefreshStatus, context)
            }
        }

        private suspend fun executeCallEndLogic(
            subId: Int,
            context: CallTrackingContext,
        ): Boolean {
            val effectiveSubId = attributionResolver.resolveEffectiveSubId(subId)
            if (effectiveSubId != -1) {
                return endCall(effectiveSubId, true, context)
            }

            val result = callEndResolver.resolveUnknownSubIdEnd()
            return handleResolveResult(result, context)
        }

        private suspend fun handleResolveResult(
            result: CallEndResolver.ResolveResult,
            context: CallTrackingContext,
        ): Boolean =
            when (result) {
                is CallEndResolver.ResolveResult.Error -> {
                    context.updateStatus(result.error)
                    false
                }

                is CallEndResolver.ResolveResult.SubIds -> {
                    processResolvedSubIds(result.ids, context)
                    true
                }
            }

        private suspend fun processResolvedSubIds(
            subIdsToEnd: List<Int>,
            context: CallTrackingContext,
        ) {
            if (subIdsToEnd.isEmpty()) return

            val notifyEach = subIdsToEnd.size == 1
            subIdsToEnd.forEach { id ->
                try {
                    endCall(id, notifyEach, context)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Failed to end call for subId $id during resolution", e)
                }
            }
            if (!notifyEach) {
                dataChangeNotifier.notifyDataChanged()
            }
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
            // The transitionStatus implementation in CallTracker handles this priority.
            context.updateStatus(error)
            if (subId != -1) {
                try {
                    sessionCleaner.cleanupCounters(subId, context)
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    AppLogger.e("Cleanup failed in onCallEnded catch block", ex)
                }
            }
        }

        private suspend fun finalizeCallEnd(
            shouldRefreshStatus: Boolean,
            context: CallTrackingContext,
        ) {
            if (!shouldRefreshStatus) return
            withContext(NonCancellable) {
                try {
                    context.refreshStatus(false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.w("CallTracker: refreshStatus failed in finalizeCallEnd", e)
                }
            }
        }

        suspend fun endCall(
            subId: Int,
            notify: Boolean,
            context: CallTrackingContext,
        ): Boolean {
            val endData = fetchCallEndData(subId)

            if (!settingsHandler.isCallTrackingEnabled()) {
                AppLogger.d("CallTracker: tracking disabled, clearing session for subId $subId")
                sessionCleaner.cleanupCounters(subId, context)
                return true
            }
            return processCallEndLocked(subId, endData, notify, context)
        }

        private suspend fun fetchCallEndData(subId: Int): CallEndData =
            CallEndData(
                startTimeResult =
                    retryHelper.readWithRetry(
                        label = "endCall:startTime",
                    ) { callSessionRepository.getCallStartTime(subId) },
                savedBootTimeResult =
                    retryHelper.readWithRetry(
                        label = "endCall:bootTimeForDuration",
                    ) { callSessionRepository.getBootTime(subId) },
                savedIdentityResult =
                    retryHelper.readWithRetry(
                        label = "endCall:identity",
                    ) { callSessionRepository.getCallIdentity(subId) },
                savedUptimeResult =
                    retryHelper.readWithRetry(
                        label = "endCall:uptime",
                    ) { callSessionRepository.getUptime(subId) },
                currentBootTime = timeProvider.elapsedRealtime(),
                currentUptime = timeProvider.uptimeMillis(),
            )

        private suspend fun processCallEndLocked(
            subId: Int,
            data: CallEndData,
            notify: Boolean,
            context: CallTrackingContext,
        ): Boolean {
            if (data.startTimeResult.isFailure) {
                handleReadStartTimeFailure(subId, data.startTimeResult.exceptionOrNull(), context)
                return false
            } else {
                val startTime = data.startTimeResult.getOrThrow()
                if (startTime == 0L) {
                    AppLogger.d("CallTracker: received IDLE for subId $subId but no session was active")
                    return true
                } else if (validateCallDuration(subId, data, context)) {
                    val savedBootTime = data.savedBootTimeResult.getOrElse { 0L }
                    val durationSeconds =
                        ((data.currentBootTime - savedBootTime) / Constants.Time.MILLIS_IN_SECOND).toInt()

                    return if (processCallDurationLocked(
                            subId = subId,
                            durationSeconds = durationSeconds,
                            data = data,
                            notify = notify,
                            context = context,
                        )
                    ) {
                        sessionCleaner.cleanupCounters(subId, context)
                        true
                    } else {
                        false
                    }
                } else {
                    return false
                }
            }
        }

        private suspend fun validateCallDuration(
            subId: Int,
            data: CallEndData,
            context: CallTrackingContext,
        ): Boolean {
            val savedBootTime = data.savedBootTimeResult.getOrElse { 0L }
            return when {
                (savedBootTime > data.currentBootTime) ||
                    (
                        (savedBootTime > 0L) &&
                            ((data.currentBootTime - savedBootTime) > Constants.Calls.MAX_CALL_DURATION_MS)
                    ) -> {
                    val message = "Call duration exceeded max limit for subId $subId"
                    AppLogger.w("CallTracker: $message, clearing session")
                    context.updateStatus(TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, message))
                    sessionCleaner.cleanupCounters(subId, context)
                    false
                }

                savedBootTime == 0L -> {
                    AppLogger.w("CallTracker: bootTime not recorded for subId $subId, clearing session")
                    sessionCleaner.cleanupCounters(subId, context)
                    false
                }

                else -> true
            }
        }

        private suspend fun processCallDurationLocked(
            subId: Int,
            durationSeconds: Int,
            data: CallEndData,
            notify: Boolean,
            context: CallTrackingContext,
        ): Boolean =
            when {
                durationSeconds < 0 -> {
                    AppLogger.w(
                        "CallTracker: negative duration detected for subId $subId ($durationSeconds s), ignoring",
                    )
                    sessionCleaner.cleanupCounters(subId, context)
                    false
                }

                durationSeconds > (Constants.Calls.MAX_CALL_DURATION_MS / Constants.Time.MILLIS_IN_SECOND) -> {
                    AppLogger.w(
                        "CallTracker: duration $durationSeconds s exceeds max, clearing session for subId $subId",
                    )
                    sessionCleaner.cleanupCounters(subId, context)
                    false
                }

                data.savedUptimeResult.getOrElse { 0L }.let { (it > 0L) && (data.currentUptime < it) } -> {
                    AppLogger.w("CallTracker: negative uptime duration detected for subId $subId, ignoring")
                    sessionCleaner.cleanupCounters(subId, context)
                    false
                }

                else -> {
                    val savedIdentity = data.savedIdentityResult.getOrNull()
                    val (targetIdentity, isFallbackUsed) =
                        attributionResolver.resolveTargetIdentity(
                            subId,
                            savedIdentity,
                        )

                    if (durationSeconds == 0) {
                        AppLogger.d("CallTracker: zero-duration call for identity $targetIdentity, clearing session")
                        sessionCleaner.cleanupCounters(subId, context)
                        false
                    } else if (targetIdentity != null) {
                        val durationMinutes = calculateDurationMinutes(durationSeconds)
                        settingsHandler.writeCallDuration(targetIdentity, durationMinutes, isFallbackUsed, notify)
                        true
                    } else {
                        attributionResolver.handleAttributionFailure(subId, {
                            context.updateStatus(it)
                        }, { sessionCleaner.cleanupCounters(it, context) })
                        false
                    }
                }
            }

        private suspend fun handleReadStartTimeFailure(
            subId: Int,
            error: Throwable?,
            context: CallTrackingContext,
        ) {
            (error as? CancellationException)?.let { throw it }
            val message = "Timeout reading session data for subId $subId"
            AppLogger.e(message, error)
            context.updateStatus(TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, error?.message ?: message))
            sessionCleaner.cleanupCounters(subId, context)
        }

        private fun calculateDurationMinutes(seconds: Int): Int =
            ((seconds + Constants.Time.SECONDS_IN_MINUTE) - 1) / Constants.Time.SECONDS_IN_MINUTE

        internal data class CallEndData(
            val startTimeResult: Result<Long>,
            val savedBootTimeResult: Result<Long>,
            val savedIdentityResult: Result<String?>,
            val savedUptimeResult: Result<Long>,
            val currentBootTime: Long,
            val currentUptime: Long,
        )
    }
