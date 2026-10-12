package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.data.util.DataStoreRetryHelper
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.SimUsageResetRepository
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages traffic baseline (reset, retry, probe).
 *
 * Invariant: `lastTotalBytes` for an identity must always correspond to the total mobile bytes
 * in the window [lastResetDate, now] at the time of the last successful update.
 * Any baseline reset or initialization must query the same window to ensure correct delta calculation.
 *
 * Safe to call from any coroutine context.
 */
@Singleton
class TrafficBaselineManager
    @Inject
    constructor(
        private val simIdentityRepository: SimIdentityRepository,
        private val simUsageRepository: SimUsageRepository,
        private val simUsageResetRepository: SimUsageResetRepository,
        private val permissionManager: PermissionManager,
        private val timeProvider: TimeProvider,
        private val networkStatsProvider: NetworkStatsProvider,
        private val dataChangeNotifier: DataChangeNotifier,
        private val dataStoreRetryHelper: DataStoreRetryHelper,
    ) {
        /**
         * Reads the baseline retry count for the given [identity].
         *
         * @param identity SIM identity.
         * @return [Result.success] containing `null` if no retry count is set,
         * [Result.success] containing `Int` if a retry count is present,
         * or [Result.failure] if reading from DataStore failed after retries.
         */
        suspend fun getRetryCount(identity: String): Result<Int?> =
            dataStoreRetryHelper.readWithRetry("baselineRetryCount") {
                simUsageResetRepository.getBaselineRetryCount(identity)
            }

        suspend fun setRetryCount(
            identity: String,
            count: Int,
        ) {
            simUsageResetRepository.setBaselineRetryCount(identity, count)
        }

        suspend fun removeRetry(identity: String) {
            simUsageResetRepository.setBaselineRetryCount(identity, null)
        }

        suspend fun cleanupRetries(activeIdentities: Set<String>) {
            simUsageResetRepository.cleanupBaselineRetries(activeIdentities)
        }

        suspend fun removeRetriesExcept(identity: String) {
            simUsageResetRepository.cleanupBaselineRetries(setOf(identity))
        }

        /**
         * Queries current mobile bytes since [startTime].
         *
         * @param startTime must be a valid timestamp (greater than 0).
         * Typically this is the `lastResetDate` of the SIM usage.
         * If [startTime] is 0 or negative, returns [StatsResult.Error] with [IllegalArgumentException] as cause.
         * Invariant: window [startTime, now] requires now >= startTime; if violated, returns [StatsResult.Unavailable].
         * Contract: invalid window [startTime, now] (including startTime <= 0) does not invoke NetworkStatsManager.
         */
        fun getCurrentMobileBytes(startTime: Long): StatsResult =
            when {
                startTime <= 0L ->
                    StatsResult.Error(
                        IllegalArgumentException("startTime must be greater than 0, but was $startTime"),
                    )

                else -> {
                    val now = timeProvider.now()
                    when {
                        now < startTime -> {
                            AppLogger.w(
                                "TrafficBaselineManager: invalid time window, " +
                                    "now ($now) is earlier than startTime ($startTime)",
                            )
                            StatsResult.Unavailable
                        }

                        else -> networkStatsProvider.queryMobileBytes(startTime, now)
                    }
                }
            }

        suspend fun resetBaseline(
            identity: String,
            updateStatus: (TrackingStatus) -> Unit,
            incrementRetryOnFailure: Boolean = false,
            onSuccess: suspend () -> Unit = {},
        ) {
            try {
                // Ensure tracking window is initialized. If lastResetDate was 0, it becomes 'now'.
                simUsageResetRepository.ensureTrackingWindowInitialized(identity)

                val usage = simUsageRepository.getSimUsage(identity).firstWithTimeout()
                if (usage == null) {
                    AppLogger.e("TrafficBaselineManager: timeout fetching usage for $identity during baseline reset")
                    updateStatus(TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, "Timeout fetching usage"))
                    if (incrementRetryOnFailure) {
                        incrementRetryCount(identity)
                    }
                    return
                }
                val statsResult = getCurrentMobileBytes(usage.lastResetDate)

                if (statsResult is StatsResult.Success) {
                    val currentBytes = statsResult.bytes
                    val applied =
                        simUsageResetRepository.setLastTotalBytes(
                            identity,
                            currentBytes,
                            usage.lastResetDate,
                        )
                    if (applied) {
                        AppLogger.d("Traffic baseline reset for $identity to $currentBytes")

                        syncLastDataSubIdIfNeeded(identity, onSuccess)

                        removeRetry(identity)
                        dataChangeNotifier.notifyDataChanged(updateWidget = true, force = false)
                        updateStatus(TrackingStatus.Active)
                    } else {
                        AppLogger.w(
                            "TrafficBaselineManager: baseline reset write skipped due to window mismatch for $identity",
                        )
                    }
                } else {
                    val message =
                        if (statsResult is StatsResult.Error) {
                            AppLogger.e("Traffic stats baseline query failed", statsResult.exception)
                            statsResult.exception.message
                        } else {
                            "Stats unavailable"
                        }
                    updateStatus(TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, message))
                    if (incrementRetryOnFailure) {
                        incrementRetryCount(identity)
                    }
                }
            } catch (e: SecurityException) {
                AppLogger.e("TrafficBaselineManager: SecurityException during baseline reset", e)
                updateStatus(TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING, e.message))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Error in resetBaseline", e)
                updateStatus(TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message))
                if (incrementRetryOnFailure) {
                    incrementRetryCount(identity)
                }
            }
        }

        private suspend fun incrementRetryCount(identity: String) {
            val count = getRetryCount(identity).getOrDefault(null) ?: 0
            setRetryCount(identity, count + 1)
        }

        private suspend fun syncLastDataSubIdIfNeeded(
            identity: String,
            onSync: suspend () -> Unit,
        ) {
            val currentDataSubId =
                try {
                    permissionManager.getDefaultDataSubscriptionId()
                } catch (_: Exception) {
                    -1
                }
            if (currentDataSubId != -1) {
                val info = permissionManager.getActiveSubscriptions().find { it.subscriptionId == currentDataSubId }
                if (info != null) {
                    val currentIdentity = simIdentityRepository.getIdentity(info.simSlotIndex)
                    if (currentIdentity == identity) {
                        onSync()
                    }
                }
            }
        }

        companion object {
            const val MAX_BASELINE_RETRIES = 3
        }
    }
