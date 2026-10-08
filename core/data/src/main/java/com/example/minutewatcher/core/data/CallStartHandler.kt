package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.data.util.DataStoreRetryHelper
import com.example.minutewatcher.core.data.util.DataStoreTimeoutException
import com.example.minutewatcher.core.domain.CallSessionRepository
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

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
                bootTime = if (eventElapsedRealtime > 0) eventElapsedRealtime else timeProvider.elapsedRealtime(),
                uptime = timeProvider.uptimeMillis(),
            )
        }

        /**
         * Processes the call start logic. Suppressing ReturnCount due to early returns on failure.
         */
        @Suppress("ReturnCount")
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
                val savedBootTime = data.bootTimeResult.getOrElse { 0L }
                val isStale = validateSessionStaleness(targetSubId, savedBootTime, data.bootTime, context)
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
            callSessionRepository.saveCallSession(
                subId = targetSubId,
                startTime = data.now,
                bootTime = data.bootTime,
                uptime = data.uptime,
                identity = data.currentIdentity,
            )
            AppLogger.d("Call start recorded for subId $targetSubId at ${data.now} (identity=${data.currentIdentity})")
        }

        private suspend fun validateSessionStaleness(
            targetSubId: Int,
            savedBootTime: Long,
            currentBootTime: Long,
            context: CallTrackingContext,
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
                    sessionCleaner.cleanupCounters(targetSubId, context)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
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
                try {
                    context.refreshStatus(false)
                } catch (e: Exception) {
                    AppLogger.w("CallTracker: refreshStatus failed in onCallStarted finally block", e)
                }
            }
        }
    }
