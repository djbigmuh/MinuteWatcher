package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.CallSessionRepository
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class CallSessionCleaner @Inject constructor(
    private val callSessionRepository: CallSessionRepository,
    private val attributionResolver: CallAttributionResolver,
) {
    suspend fun cleanupCounters(
        subId: Int,
        statusFlow: MutableStateFlow<TrackingStatus>,
    ) {
        try {
            callSessionRepository.setCallStartTime(subId, 0L)
            callSessionRepository.setCallIdentity(subId, null)
            callSessionRepository.setBootTime(subId, 0L)
            callSessionRepository.setUptime(subId, 0L)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("Session cleanup failed for subId $subId", e)
            statusFlow.value =
                TrackingStatus.Error(
                    TrackingStatus.ErrorType.DATABASE_ERROR,
                    "Session cleanup failed: ${e.message}",
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
