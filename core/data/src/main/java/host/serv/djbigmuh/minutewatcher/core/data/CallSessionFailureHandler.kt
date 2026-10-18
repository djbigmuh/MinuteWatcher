package host.serv.djbigmuh.minutewatcher.core.data

import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import host.serv.djbigmuh.minutewatcher.core.data.util.DataStoreTimeoutException
import host.serv.djbigmuh.minutewatcher.core.model.TrackingStatus
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

        val isTimeout = error is DataStoreTimeoutException
        val message =
            if (isTimeout) {
                "Timeout reading session data for subId $subId"
            } else {
                "Failed to read session data for subId $subId"
            }

        AppLogger.e(message, error)

        val code = if (isTimeout) TrackingStatus.ErrorCode.TIMEOUT else null
        val statusMessage = error?.message ?: message

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
