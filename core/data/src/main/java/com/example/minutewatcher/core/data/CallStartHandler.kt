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
internal class CallStartHandler
    @Inject
    constructor(
        private val callSessionRepository: CallSessionRepository,
        private val timeProvider: TimeProvider,
        private val retryHelper: DataStoreRetryHelper,
        private val attributionResolver: CallAttributionResolver,
    ) {
    suspend fun executeCallStart(
        subId: Int,
        callMutex: Mutex,
        statusFlow: MutableStateFlow<TrackingStatus>,
        checkPermissions: () -> Boolean,
        cleanupCounters: suspend (Int) -> Unit,
        refreshStatus: suspend (Boolean) -> Unit,
        isCallTrackingEnabled: suspend () -> Boolean,
    ) {
        var shouldRefreshStatus = false
        try {
            if (!checkPermissions()) {
                shouldRefreshStatus = true
                return
            }

            val targetSubId = attributionResolver.resolveTargetSubId(subId)
            if (targetSubId == -1) {
                AppLogger.w("CallTracker: could not attribute start event (unknown subId and no default SIM)")
                shouldRefreshStatus = true
                return
            }

            callMutex.withLock {
                if (!isCallTrackingEnabled()) {
                    AppLogger.d("CallTracker: tracking disabled, ignoring OFFHOOK for subId $targetSubId")
                    cleanupCounters(targetSubId)
                    shouldRefreshStatus = true
                    return@withLock
                }

                val startData = fetchCallStartData(targetSubId)
                processCallStartLocked(targetSubId, startData, statusFlow, cleanupCounters)
                shouldRefreshStatus = true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            shouldRefreshStatus = true
            statusFlow.value = TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR, e.message)
        } catch (e: SecurityException) {
            shouldRefreshStatus = true
            AppLogger.e("SecurityException in onCallStarted", e)
            statusFlow.value = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING, e.message)
        } catch (e: Exception) {
            shouldRefreshStatus = true
            AppLogger.e("Error in onCallStarted", e)
            if (statusFlow.value !is TrackingStatus.Error) {
                statusFlow.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
            }
        } finally {
            finalizeCallStart(shouldRefreshStatus, refreshStatus)
        }
    }

    private suspend fun fetchCallStartData(targetSubId: Int): CallStartData {
        val startTimeResult =
            retryHelper.readWithRetry(
                label = "getCallStartTime",
            ) { callSessionRepository.getCallStartTime(targetSubId) }
        val bootTimeResult =
            retryHelper.readWithRetry(
                label = "getBootTime",
            ) { callSessionRepository.getBootTime(targetSubId) }
        val savedIdentityResult =
            retryHelper.readWithRetry(
                label = "getCallIdentity",
            ) { callSessionRepository.getCallIdentity(targetSubId) }

        val currentIdentity = attributionResolver.resolveIdentity(targetSubId)
        return CallStartData(
            startTimeResult = startTimeResult,
            bootTimeResult = bootTimeResult,
            identity = savedIdentityResult.getOrNull(),
            currentIdentity = currentIdentity,
            now = timeProvider.now(),
            bootTime = timeProvider.elapsedRealtime(),
            uptime = timeProvider.uptimeMillis(),
        )
    }

    private suspend fun processCallStartLocked(
        targetSubId: Int,
        data: CallStartData,
        statusFlow: MutableStateFlow<TrackingStatus>,
        cleanupCounters: suspend (Int) -> Unit,
    ) {
        if (data.startTimeResult.isFailure) {
            handleStartTimeFailure(targetSubId, data.startTimeResult.exceptionOrNull(), statusFlow, cleanupCounters)
        } else {
            val startTime = data.startTimeResult.getOrThrow()
            if (startTime != 0L) {
                val savedBootTime = data.bootTimeResult.getOrElse { 0L }
                val isStale = validateSessionStaleness(targetSubId, savedBootTime, data.bootTime, cleanupCounters)
                if (!isStale) {
                    handleActiveSession(targetSubId, data)
                    return
                }
            }

            recordCallStart(targetSubId, data)
        }
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
        callSessionRepository.setCallStartTime(targetSubId, data.now)
        callSessionRepository.setBootTime(targetSubId, data.bootTime)
        callSessionRepository.setUptime(targetSubId, data.uptime)
        callSessionRepository.setCallIdentity(targetSubId, data.currentIdentity)
        AppLogger.d("Call start recorded for subId $targetSubId at ${data.now} (identity=${data.currentIdentity})")
    }

    private suspend fun validateSessionStaleness(
        targetSubId: Int,
        savedBootTime: Long,
        currentBootTime: Long,
        cleanupCounters: suspend (Int) -> Unit,
    ): Boolean {
        val isStale =
            (savedBootTime > currentBootTime) ||
                (
                    (savedBootTime > 0) &&
                        ((currentBootTime - savedBootTime) > Constants.Calls.MAX_CALL_DURATION_MS)
                    )

        if (isStale) {
            AppLogger.w("CallTracker: clearing stale session for subId $targetSubId")
            try {
                cleanupCounters(targetSubId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Non-fatal error clearing stale session", e)
            }
        }
        return isStale
    }

    private suspend fun handleStartTimeFailure(
        targetSubId: Int,
        error: Throwable?,
        statusFlow: MutableStateFlow<TrackingStatus>,
        cleanupCounters: suspend (Int) -> Unit,
    ) {
        (error as? CancellationException)?.let { throw it }
        val message = "Timeout reading session data for subId $targetSubId"
        AppLogger.e(message, error)
        statusFlow.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, error?.message ?: message)
        cleanupCounters(targetSubId)
    }

    private suspend fun finalizeCallStart(
        shouldRefreshStatus: Boolean,
        refreshStatus: suspend (Boolean) -> Unit,
    ) {
        withContext(NonCancellable) {
            try {
                if (shouldRefreshStatus) {
                    refreshStatus(false)
                }
            } catch (e: Exception) {
                AppLogger.w("CallTracker: refreshStatus failed in onCallStarted finally block", e)
            }
        }
    }
}
