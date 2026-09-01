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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
    private val inMemoryLastDataSubId = AtomicInteger(-1)
    private val lastDataSubIdLoaded = AtomicBoolean(false)

        suspend fun <T> withSimChangeLock(block: suspend () -> T): T = simChangeMutex.withLock { block() }

        suspend fun ensureInMemoryLastDataSubId() {
            if (!lastDataSubIdLoaded.get()) {
                try {
                    val loaded =
                        withTimeoutOrNull(Constants.Flow.LONG_TIMEOUT_MS.milliseconds) {
                            callSessionRepository.getLastDataSubId().first()
                        }
                    if (loaded != null) {
                        inMemoryLastDataSubId.set(loaded)
                        lastDataSubIdLoaded.set(true)
                    } else {
                        AppLogger.w("TrafficSimChangeHandler: Timeout loading lastDataSubId")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("TrafficSimChangeHandler: Failed to load lastDataSubId", e)
                }
            }
        }

        suspend fun handleSimChangeIfNeeded(
            target: TargetInfo,
            status: MutableStateFlow<TrackingStatus>,
        ): String? {
            ensureInMemoryLastDataSubId()
            val savedLastDataSubId = inMemoryLastDataSubId.get()
            val targetIdentity = simIdentityRepository.getIdentity(target.slot)

            val isSimChanged =
                (savedLastDataSubId != -1) &&
                    (target.dataSubId != -1) &&
                    (savedLastDataSubId != target.dataSubId)

            // We always call handleSimChange to ensure the retry map is cleaned up for other identities.
            // Logic for actual SIM change processing is contained within handleSimChange.
            handleSimChange(
                targetIdentity,
                target.dataSubId,
                savedLastDataSubId,
                status,
                isSimChanged,
            )

            return if (isSimChanged) {
                // Read the updated retry count from the map to accurately log the state after the attempt.
                val retryCount = baselineManager.baselineNeedsRetry.getOrDefault(targetIdentity, 0)
                handleSkipDelta(target, retryCount, status)
                null
            } else {
                targetIdentity
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
            identity: String,
            currentDataSubId: Int,
            savedLastDataSubId: Int,
            status: MutableStateFlow<TrackingStatus>,
            isSimChanged: Boolean,
        ) {
            // Centralized cleanup: remove all retries for other identities
            baselineManager.baselineNeedsRetry.entries.removeIf { it.key != identity }

            if (!isSimChanged) return

            AppLogger.d("Default data SIM changed from $savedLastDataSubId to $currentDataSubId. Resetting baseline.")
            try {
                // Update the last seen data subId immediately to "commit" the detection of the SIM change.
                // This prevents the same SIM change from being repeatedly detected if the baseline
                // reset operation fails and enters a retry cycle.
                updateLastDataSubId(currentDataSubId)

                val retryCount = baselineManager.baselineNeedsRetry.getOrDefault(identity, 0)
                if (retryCount >= TrafficBaselineManager.MAX_BASELINE_RETRIES) {
                    AppLogger.e(
                        "TrafficSimChangeHandler: Baseline reset failed after $retryCount retries for $identity",
                    )
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
                status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
            }
        }

        suspend fun updateLastDataSubId(dataSubId: Int) {
            lastDataSubIdLoaded.set(true)
            if ((dataSubId == -1) || (dataSubId == inMemoryLastDataSubId.get())) return
            inMemoryLastDataSubId.set(dataSubId)
            try {
                callSessionRepository.setLastDataSubId(dataSubId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("TrafficSimChangeHandler: Failed to persist last data subId $dataSubId", e)
            }
        }
    }
