package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.data.util.DataStoreRetryHelper
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                shouldRefreshStatus = false
                context.updateStatus(TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR, e.message))
            } catch (e: SecurityException) {
                shouldRefreshStatus = false
                AppLogger.e("SecurityException in onCallStarted", e)
                context.updateStatus(TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING, e.message))
            } catch (e: Exception) {
                shouldRefreshStatus = false
                AppLogger.e("Error in onCallStarted", e)
                val error = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
                context.updateStatus(error)
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

        @Suppress("ReturnCount")
        private suspend fun processCallStartLocked(
            targetSubId: Int,
            data: CallStartData,
            context: CallTrackingContext,
        ): Boolean {
            if (data.startTimeResult.isFailure) {
                handleStartTimeFailure(targetSubId, data.startTimeResult.exceptionOrNull(), context)
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

        private suspend fun handleStartTimeFailure(
            targetSubId: Int,
            error: Throwable?,
            context: CallTrackingContext,
        ) {
            (error as? CancellationException)?.let { throw it }
            val message = "Timeout reading session data for subId $targetSubId"
            AppLogger.e(message, error)
            context.updateStatus(TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, error?.message ?: message))
            sessionCleaner.cleanupCounters(targetSubId, context)
        }

        /**
         * Finalizes the call start process by optionally refreshing the tracker status.
         *
         * **Cleanup invariant:** This block does not rethrow exceptions, including [CancellationException].
         * The cancellation of the outer scope should be checked by the caller.
         */
        private suspend fun finalizeCallStart(
            shouldRefreshStatus: Boolean,
            context: CallTrackingContext,
        ) {
            withContext(NonCancellable) {
                try {
                    if (shouldRefreshStatus) {
                        context.refreshStatus(false)
                    }
                } catch (e: Exception) {
                    AppLogger.w("CallTracker: refreshStatus failed in onCallStarted finally block", e)
                }
            }
        }
    }
