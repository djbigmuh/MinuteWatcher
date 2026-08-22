package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.CallSessionRepository
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageResetRepository
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * Handles SIM card changes and retry logic for traffic tracking.
 *
 * Safe to call from any coroutine context.
 */
@Singleton
class TrafficSimChangeHandler
    @Inject
    constructor(
        private val callSessionRepository: CallSessionRepository,
        private val simIdentityRepository: SimIdentityRepository,
        private val simUsageResetRepository: SimUsageResetRepository,
        private val baselineManager: TrafficBaselineManager,
    ) {
        private val simChangeMutex = Mutex()
        private var inMemoryLastDataSubId: Int = -1

        suspend fun <T> withSimChangeLock(block: suspend () -> T): T = simChangeMutex.withLock { block() }

        suspend fun ensureInMemoryLastDataSubId() {
            if (inMemoryLastDataSubId != -1) return
            inMemoryLastDataSubId =
                withTimeoutOrNull(Constants.Flow.LONG_TIMEOUT_MS.milliseconds) {
                    callSessionRepository.getLastDataSubId().first()
                } ?: -1
        }

        suspend fun handleSimChangeIfNeeded(
            target: TargetInfo,
            status: MutableStateFlow<TrackingStatus>,
            onNoChange: suspend (String) -> Unit,
        ) {
            ensureInMemoryLastDataSubId()
            val savedLastDataSubId = inMemoryLastDataSubId
            val isSimChanged =
                (savedLastDataSubId != -1) &&
                    (target.dataSubId != -1) &&
                    (savedLastDataSubId != target.dataSubId)

            if (isSimChanged) {
                val targetIdentity = simIdentityRepository.getIdentity(target.slot)
                val retryCount = baselineManager.baselineNeedsRetry.getOrDefault(targetIdentity, 0)
                handleSimChange(target.slot, target.dataSubId, savedLastDataSubId, retryCount, status)
                handleSkipDelta(target, retryCount, status)
            } else {
                val targetIdentity = simIdentityRepository.getIdentity(target.slot)
                baselineManager.baselineNeedsRetry.entries.removeIf { it.key != targetIdentity }
                onNoChange(targetIdentity)
            }
        }

        private fun handleSkipDelta(
            target: TargetInfo,
            retryCount: Int,
            status: MutableStateFlow<TrackingStatus>,
        ) {
            AppLogger.d("Traffic delta skipped due to SIM change (slot=${target.slot}, retryCount=$retryCount)")
            if (status.value !is TrackingStatus.Error) {
                status.value = TrackingStatus.Active
            }
        }

        private suspend fun handleSimChange(
            targetSim: Int,
            currentDataSubId: Int,
            savedLastDataSubId: Int,
            retryCount: Int,
            status: MutableStateFlow<TrackingStatus>,
        ) {
            AppLogger.d("Default data SIM changed from $savedLastDataSubId to $currentDataSubId. Resetting baseline.")
            try {
                val identity = simIdentityRepository.getIdentity(targetSim)
                baselineManager.baselineNeedsRetry.entries.removeIf { it.key != identity }

                if (retryCount >= TrafficBaselineManager.MAX_BASELINE_RETRIES) {
                    AppLogger.e(
                        "TrafficSimChangeHandler: Baseline reset failed after $retryCount retries for $identity",
                    )
                    updateLastDataSubId(currentDataSubId)
                    baselineManager.baselineNeedsRetry.remove(identity)
                    status.value =
                        TrackingStatus.Error(
                            TrackingStatus.ErrorType.DATABASE_ERROR,
                            "Baseline reset failed after $retryCount retries for $identity",
                        )
                    return
                }

                val statsResult = baselineManager.getCurrentMobileBytes(0L)
                if (statsResult is StatsResult.Success) {
                    simUsageResetRepository.setLastTotalBytes(identity, statsResult.bytes)
                    baselineManager.baselineNeedsRetry.remove(identity)
                    updateLastDataSubId(currentDataSubId)
                    AppLogger.d("Baseline reset successfully for $identity")
                    status.value = TrackingStatus.Active
                } else {
                    val reason =
                        if (statsResult is StatsResult.Error) {
                            AppLogger.e("Traffic stats query failed on SIM change", statsResult.exception)
                            statsResult.exception.message
                        } else {
                            "Stats unavailable"
                        }
                    AppLogger.w("Stats unavailable on SIM change for $identity: $reason. Retrying.")
                    baselineManager.baselineNeedsRetry[identity] = retryCount + 1
                    status.value = TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR, reason)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Failed to handle SIM change", e)
            }
        }

        suspend fun updateLastDataSubId(dataSubId: Int) {
            if ((dataSubId == -1) || (dataSubId == inMemoryLastDataSubId)) return
            inMemoryLastDataSubId = dataSubId
            try {
                callSessionRepository.setLastDataSubId(dataSubId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("TrafficSimChangeHandler: Failed to persist last data subId $dataSubId", e)
            }
        }
    }
