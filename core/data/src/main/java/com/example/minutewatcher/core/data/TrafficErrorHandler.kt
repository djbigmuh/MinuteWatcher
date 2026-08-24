package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrafficErrorHandler
    @Inject
    constructor() {
        fun handleUpdateError(
            e: Throwable,
            statusFlow: MutableStateFlow<TrackingStatus>,
        ) {
            if (e is SecurityException) {
                AppLogger.e("SecurityException in TrafficTracker", e)
                statusFlow.value =
                    TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING, e.message)
            } else {
                AppLogger.e("Error updating traffic", e)
                statusFlow.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
            }
        }

        fun handleSimListTimeout(statusFlow: MutableStateFlow<TrackingStatus>) {
            AppLogger.e("TrafficTracker: observeActiveSims timed out after retry")
            statusFlow.value =
                TrackingStatus.Error(
                    TrackingStatus.ErrorType.UNKNOWN,
                    "SIM list read timeout",
                )
        }

        fun handleInconsistentFirmware(statusFlow: MutableStateFlow<TrackingStatus>) {
            AppLogger.w("TrafficTracker: inconsistent firmware state")
            statusFlow.value = TrackingStatus.Error(TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION)
        }

        fun handleBaselineRetryFailure(
            identity: String,
            statusFlow: MutableStateFlow<TrackingStatus>,
        ) {
            AppLogger.e("TrafficTracker: baseline reset for $identity failed after all retries")
            statusFlow.value =
                TrackingStatus.Error(
                    TrackingStatus.ErrorType.DATABASE_ERROR,
                    "Baseline reset failed for $identity",
                )
        }

        fun handleUsageReadTimeout(
            identity: String,
            statusFlow: MutableStateFlow<TrackingStatus>,
        ) {
            AppLogger.e("TrafficTracker: getSimUsage timed out for $identity")
            statusFlow.value =
                TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, "Timeout reading simUsage")
        }

        fun handleLastTotalBytesTimeout(
            identity: String,
            statusFlow: MutableStateFlow<TrackingStatus>,
        ) {
            AppLogger.e("TrafficTracker: getLastTotalBytes timed out for $identity")
            statusFlow.value =
                TrackingStatus.Error(
                    TrackingStatus.ErrorType.UNKNOWN,
                    "Timeout reading lastTotalBytes",
                )
        }

        fun handleStatsFailure(
            statsResult: StatsResult,
            statusFlow: MutableStateFlow<TrackingStatus>,
        ) {
            val message =
                if (statsResult is StatsResult.Error) {
                    statsResult.exception.message
                } else {
                    "Stats unavailable"
                }
            statusFlow.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, message)
        }
    }
