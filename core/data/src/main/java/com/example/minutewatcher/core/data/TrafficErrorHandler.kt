package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.model.TrackingStatus
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrafficErrorHandler
    @Inject
    constructor() {
        fun handleUpdateError(
            e: Throwable,
            updateStatus: (TrackingStatus) -> Unit,
        ) {
            when (e) {
                is SecurityException -> {
                    AppLogger.e("SecurityException in TrafficTracker", e)
                    updateStatus(
                        TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING, e.message),
                    )
                }

                is IOException -> {
                    AppLogger.e("IOException in TrafficTracker", e)
                    updateStatus(
                        TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR, e.message),
                    )
                }

                else -> {
                    AppLogger.e("Error updating traffic", e)
                    updateStatus(TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message))
                }
            }
        }

        fun handleInconsistentFirmware(updateStatus: (TrackingStatus) -> Unit) {
            AppLogger.w("TrafficTracker: inconsistent firmware state")
            updateStatus(TrackingStatus.Error(TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION))
        }

        fun handleBaselineRetryFailure(
            identity: String,
            updateStatus: (TrackingStatus) -> Unit,
        ) {
            AppLogger.e("TrafficTracker: baseline reset for $identity failed after all retries")
            updateStatus(
                TrackingStatus.Error(
                    TrackingStatus.ErrorType.DATABASE_ERROR,
                    "Baseline reset failed for $identity",
                ),
            )
        }

        fun handleUsageReadTimeout(
            identity: String,
            updateStatus: (TrackingStatus) -> Unit,
        ) {
            AppLogger.e("TrafficTracker: getSimUsage timed out for $identity")
            updateStatus(
                TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, "Timeout reading simUsage"),
            )
        }

        fun handleLastTotalBytesTimeout(
            identity: String,
            updateStatus: (TrackingStatus) -> Unit,
        ) {
            AppLogger.e("TrafficTracker: getLastTotalBytes timed out for $identity")
            updateStatus(
                TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, "Timeout reading lastTotalBytes"),
            )
        }

        fun handleStatsFailure(
            statsResult: StatsResult,
            updateStatus: (TrackingStatus) -> Unit,
        ) {
            val message =
                if (statsResult is StatsResult.Error) {
                    AppLogger.e("Traffic stats query failed", statsResult.exception)
                    statsResult.exception.message
                } else {
                    AppLogger.w("Traffic stats unavailable")
                    "Stats unavailable"
                }
            updateStatus(TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, message))
        }
    }
