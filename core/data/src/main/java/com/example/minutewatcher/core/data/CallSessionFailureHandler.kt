package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.data.util.DataStoreReadTimeoutException
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CancellationException

internal object CallSessionFailureHandler {
    suspend fun handleReadStartTimeFailure(
        subId: Int,
        error: Throwable?,
        context: CallTrackingContext,
        sessionCleaner: CallSessionCleaner,
    ) {
        if (error is CancellationException) {
            throw error
        }

        val isTimeout = error is DataStoreReadTimeoutException
        val defaultMessage =
            if (isTimeout) {
                "Timeout reading session data for subId $subId"
            } else {
                "Failed to read session data for subId $subId"
            }

        val logMessage =
            if (isTimeout) {
                "Timeout reading session data for subId $subId"
            } else {
                "Failed to read session data for subId $subId"
            }
        AppLogger.e(logMessage, error)

        val code = if (isTimeout) TrackingStatus.ErrorCode.TIMEOUT else null
        val statusMessage = error?.message ?: defaultMessage

        context.updateStatus(
            TrackingStatus.Error(
                type = TrackingStatus.ErrorType.UNKNOWN,
                message = statusMessage,
                code = code,
            ),
        )
        sessionCleaner.cleanupCounters(subId, context)
    }
}
