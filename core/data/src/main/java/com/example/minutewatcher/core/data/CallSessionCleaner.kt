package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.CallSessionRepository
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class CallSessionCleaner
    @Inject
    constructor(
        private val callSessionRepository: CallSessionRepository,
        private val attributionResolver: CallAttributionResolver,
    ) {
        suspend fun cleanupCounters(
            subId: Int,
            context: CallTrackingContext,
        ) {
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
    }
