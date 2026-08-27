package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.data.util.DataStoreRetryHelper
import com.example.minutewatcher.core.domain.CallSessionRepository
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@Suppress("LongParameterList", "CyclomaticComplexMethod", "TooManyFunctions")
internal class CallEndHandler
    @Inject
    constructor(
        private val callSessionRepository: CallSessionRepository,
        private val timeProvider: TimeProvider,
        private val retryHelper: DataStoreRetryHelper,
        private val attributionResolver: CallAttributionResolver,
    ) {
    @Suppress("LongMethod", "ThrowsCount")
        suspend fun performCallEnd(
            subId: Int,
            callEndResolver: CallEndResolver,
            statusFlow: MutableStateFlow<TrackingStatus>,
            checkPermissions: () -> Boolean,
            cleanupCounters: suspend (Int) -> Unit,
            refreshStatus: suspend (Boolean) -> Unit,
            endCallAction: suspend (Int) -> Unit,
            isCallTrackingEnabled: suspend () -> Boolean,
            callMutex: Mutex,
            clearAllCallSessions: suspend () -> Unit,
        ) {
            var operationCompleted = false
            try {
                if (!checkPermissions()) {
                    operationCompleted = true
                    return
                }

                callMutex.withLock {
                    if (subId == -1 && !isCallTrackingEnabled()) {
                        // If tracking is disabled and subId is unknown (-1), we clear all pending sessions
                        // to ensure no orphaned sessions remain when tracking is turned back on.
                        AppLogger.d("CallTracker: tracking disabled, clearing all sessions")
                        clearAllCallSessions()
                    } else {
                        executeCallEndLogic(
                            subId,
                            endCallAction,
                            callEndResolver,
                        )
                    }
                    operationCompleted = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                operationCompleted = true
                statusFlow.value = TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR, e.message)
                throw e
            } catch (e: SecurityException) {
                operationCompleted = true
                AppLogger.e("SecurityException in onCallEnded", e)
                statusFlow.value = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING, e.message)
            } catch (e: Exception) {
                operationCompleted = true
                handlePerformCallEndError(subId, e, statusFlow, cleanupCounters)
            } finally {
                finalizeCallEnd(operationCompleted, statusFlow, refreshStatus)
            }
    }

    private suspend fun executeCallEndLogic(
        subId: Int,
        endCallAction: suspend (Int) -> Unit,
        callEndResolver: CallEndResolver,
    ) {
        val effectiveSubId = attributionResolver.resolveEffectiveSubId(subId)
        if (effectiveSubId == -1) {
            callEndResolver.resolveUnknownSubIdEnd()
        } else {
            endCallAction(effectiveSubId)
        }
    }

    private suspend fun handlePerformCallEndError(
        subId: Int,
        e: Exception,
        statusFlow: MutableStateFlow<TrackingStatus>,
        cleanupCounters: suspend (Int) -> Unit,
    ) {
        AppLogger.e("Error in onCallEnded", e)
        if (statusFlow.value !is TrackingStatus.Error) {
            statusFlow.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
        }
        if (subId != -1) {
            try {
                cleanupCounters(subId)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                AppLogger.e("Cleanup failed in onCallEnded catch block", ex)
            }
        }
    }

    @Suppress("ThrowingExceptionFromFinally")
    private suspend fun finalizeCallEnd(
        operationCompleted: Boolean,
        statusFlow: MutableStateFlow<TrackingStatus>,
        refreshStatus: suspend (Boolean) -> Unit,
    ) {
        withContext(NonCancellable) {
            try {
                if (operationCompleted || (statusFlow.value is TrackingStatus.Error)) {
                    refreshStatus(false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("CallTracker: refreshStatus failed in onCallEnded finally block", e)
                }
            }
        }

        /**
         * Завершает звонок для указанного subId.
         * Требует внешней блокировки.
         */
        suspend fun endCall(
            subId: Int,
            notify: Boolean,
            statusFlow: MutableStateFlow<TrackingStatus>,
            cleanupCounters: suspend (Int) -> Unit,
            writeCallDuration: suspend (String, Int, Boolean, Boolean) -> Boolean,
            isCallTrackingEnabled: suspend () -> Boolean,
        ) {
            val endData = fetchCallEndData(subId)

            if (!isCallTrackingEnabled()) {
                AppLogger.d("CallTracker: tracking disabled, clearing session for subId $subId")
                cleanupCounters(subId)
                return
            }
            processCallEndLocked(subId, endData, notify, statusFlow, cleanupCounters, writeCallDuration)
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
            statusFlow: MutableStateFlow<TrackingStatus>,
            cleanupCounters: suspend (Int) -> Unit,
            writeCallDuration: suspend (String, Int, Boolean, Boolean) -> Boolean,
        ) {
            if (data.startTimeResult.isFailure) {
                handleReadStartTimeFailure(subId, data.startTimeResult.exceptionOrNull(), statusFlow, cleanupCounters)
            } else {
                val startTime = data.startTimeResult.getOrThrow()
                if (startTime == 0L) {
                    AppLogger.d("CallTracker: received IDLE for subId $subId but no session was active")
                } else if (validateCallDuration(subId, data, statusFlow, cleanupCounters)) {
                    val savedBootTime = data.savedBootTimeResult.getOrElse { 0L }
                    val durationSeconds =
                        ((data.currentBootTime - savedBootTime) / Constants.Time.MILLIS_IN_SECOND).toInt()

                    if (processCallDurationLocked(
                            subId = subId,
                            durationSeconds = durationSeconds,
                            data = data,
                            notify = notify,
                            statusFlow = statusFlow,
                            cleanupCounters = cleanupCounters,
                            writeCallDuration = writeCallDuration,
                        )
                    ) {
                        cleanupCounters(subId)
                    }
                }
            }
        }

        private suspend fun validateCallDuration(
            subId: Int,
            data: CallEndData,
            statusFlow: MutableStateFlow<TrackingStatus>,
            cleanupCounters: suspend (Int) -> Unit,
        ): Boolean {
            val savedBootTime = data.savedBootTimeResult.getOrElse { 0L }
            return when {
                (savedBootTime > data.currentBootTime) ||
                    (
                        savedBootTime > 0 &&
                            data.currentBootTime - savedBootTime > Constants.Calls.MAX_CALL_DURATION_MS
                    ) -> {
                    val message = "Call duration exceeded max limit for subId $subId"
                    AppLogger.w("CallTracker: $message, clearing session")
                    statusFlow.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, message)
                    cleanupCounters(subId)
                    false
                }
                savedBootTime == 0L -> {
                    AppLogger.w("CallTracker: bootTime not recorded for subId $subId, clearing session")
                    cleanupCounters(subId)
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
            statusFlow: MutableStateFlow<TrackingStatus>,
            cleanupCounters: suspend (Int) -> Unit,
            writeCallDuration: suspend (String, Int, Boolean, Boolean) -> Boolean,
        ): Boolean =
            when {
                durationSeconds < 0 -> {
                    AppLogger.w(
                        "CallTracker: negative duration detected for subId $subId ($durationSeconds s), ignoring",
                    )
                    cleanupCounters(subId)
                    false
                }
                durationSeconds > Constants.Calls.MAX_CALL_DURATION_MS / Constants.Time.MILLIS_IN_SECOND -> {
                    AppLogger.w(
                        "CallTracker: duration $durationSeconds s exceeds max, clearing session for subId $subId",
                    )
                    cleanupCounters(subId)
                    false
                }
                data.savedUptimeResult.getOrElse { 0L }.let { it > 0 && data.currentUptime < it } -> {
                    AppLogger.w("CallTracker: negative uptime duration detected for subId $subId, ignoring")
                    cleanupCounters(subId)
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
                        cleanupCounters(subId)
                        false
                    } else if (targetIdentity != null) {
                        val durationMinutes = calculateDurationMinutes(durationSeconds)
                        writeCallDuration(targetIdentity, durationMinutes, isFallbackUsed, notify)
                        true
                    } else {
                        attributionResolver.handleAttributionFailure(subId, statusFlow, cleanupCounters)
                        false
                    }
                }
            }

        private suspend fun handleReadStartTimeFailure(
            subId: Int,
            error: Throwable?,
            statusFlow: MutableStateFlow<TrackingStatus>,
            cleanupCounters: suspend (Int) -> Unit,
        ) {
            if (error is CancellationException) throw error
            val message = "Timeout reading session data for subId $subId"
            AppLogger.e(message, error)
            statusFlow.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, error?.message ?: message)
            cleanupCounters(subId)
        }

        private fun calculateDurationMinutes(seconds: Int): Int =
            (seconds + Constants.Time.SECONDS_IN_MINUTE - 1) / Constants.Time.SECONDS_IN_MINUTE
    }
