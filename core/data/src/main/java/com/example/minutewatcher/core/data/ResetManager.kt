package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.domain.AutoResetUseCase
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.SimUsageResetRepository
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.SimUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

/**
 * Manages automatic resets of SIM usage stats based on billing cycles.
 *
 * Implementation of [AutoResetUseCase].
 * Safe to call from any coroutine context.
 */
@Singleton
class ResetManager
    @Inject
    constructor(
        private val simIdentityRepository: SimIdentityRepository,
        private val simUsageRepository: SimUsageRepository,
        private val simUsageResetRepository: SimUsageResetRepository,
        private val trafficTracker: TrafficTracker,
        private val dataChangeNotifier: DataChangeNotifier,
        private val timeProvider: TimeProvider,
    ) : AutoResetUseCase {
        /**
         * High-level mutex to coordinate the overall auto-reset process across all SIMs.
         * This complements the lower-level [SimUsageRepositoryImpl.resetMutex] which ensures
         * atomicity for individual SIM reset operations.
         */
        private val resetMutex = Mutex()
    private val lastResetCheck = ConcurrentHashMap<String, Long>()
    private val pendingResets = ConcurrentHashMap.newKeySet<String>()

        /**
         * Checks and performs automatic resets for all active SIMs.
         *
         * @param shouldNotify True if a data change notification should be broadcasted after reset.
         * Safe to call from any coroutine context.
         * @return `true` if all SIMs were successfully processed, `false` otherwise.
         * @throws kotlinx.coroutines.CancellationException if the reset process is cancelled.
         */
        @Suppress("CyclomaticComplexMethod")
        override suspend fun performAutoReset(shouldNotify: Boolean): Boolean {
            val now = timeProvider.now()
            val nowElapsed = timeProvider.elapsedRealtime()

            val activeSims =
                simIdentityRepository.observeActiveSims().firstWithTimeout()
                    ?: run {
                        AppLogger.e("ResetManager: Timeout observing active SIMs during auto-reset check")
                        return false
                    }

            if (activeSims.isEmpty()) {
                AppLogger.w("ResetManager: No active SIMs detected during auto-reset check.")
            }

            val resetIdentities = mutableSetOf<String>()
            var ioException: IOException? = null
            var successCount = 0

            val allProcessed =
                withTimeoutOrNull(RESET_TOTAL_TIMEOUT_MS.milliseconds) {
                    for (simInfo in activeSims) {
                        try {
                            if (processSingleSimReset(simInfo, now, nowElapsed, resetIdentities)) {
                                successCount++
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: IOException) {
                            AppLogger.e(
                                "ResetManager: reset failed for SIM ${simInfo.identity.stableId}, continuing",
                                e,
                            )
                            ioException = e
                        } catch (e: Exception) {
                            AppLogger.e("ResetManager: Unexpected error resetting SIM ${simInfo.identity.stableId}", e)
                        }
                    }
                    true
                } ?: run {
                    AppLogger.e("ResetManager: total reset timeout after ${RESET_TOTAL_TIMEOUT_MS}ms")
                    false
                }

            cleanupStaleCheckTimestamps(activeSims)

            if (resetIdentities.isNotEmpty()) {
                resetIdentities.forEach { identity ->
                    try {
                        trafficTracker.resetBaseline(identity)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.e("ResetManager: failed to reset traffic baseline for $identity", e)
                    }
                }
                if (shouldNotify) dataChangeNotifier.notifyDataChanged()
            }

            return allProcessed && handleAutoResetResult(ioException, successCount, activeSims.size)
        }

        private suspend fun processSingleSimReset(
            simInfo: ActiveSimInfo,
            now: Long,
            nowElapsed: Long,
            resetIdentities: MutableSet<String>,
        ): Boolean {
            val identity = simInfo.identity.stableId

            val shouldCheck =
                resetMutex.withLock {
                    val lastCheck = lastResetCheck[identity]
                    val isCoolingDown = (lastCheck != null) && ((nowElapsed - lastCheck) < CHECK_INTERVAL_MS)
                    if (isCoolingDown || !pendingResets.add(identity)) {
                        false
                    } else {
                        true
                    }
                }

            if (!shouldCheck) return true // Skip or already pending is not a failure of the coordinator

            return try {
                checkAndPerformSingleReset(simInfo, now, resetIdentities)
            } finally {
                resetMutex.withLock {
                    lastResetCheck[identity] = nowElapsed
                }
                pendingResets.remove(identity)
            }
        }

    private suspend fun checkAndPerformSingleReset(
        simInfo: ActiveSimInfo,
        now: Long,
        resetIdentities: MutableSet<String>,
    ): Boolean {
        val identity = simInfo.identity.stableId
        val usage =
            withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                simUsageRepository.getSimUsage(identity).first()
            }

        if (usage == null) {
            AppLogger.w("ResetManager: Timeout reading usage for SIM $identity, skipping reset check.")
            return false
        }

        val resetPerformed = checkAndPerformResetForSimInternal(simInfo, now, usage)
        if (resetPerformed) {
            resetIdentities.add(identity)
        }
        return true
        }

        private suspend fun cleanupStaleCheckTimestamps(
            activeSims: List<com.example.minutewatcher.core.model.ActiveSimInfo>,
        ) {
            resetMutex.withLock {
                val activeIdentitySet = activeSims.asSequence().map { it.identity.stableId }.toSet()
                lastResetCheck.keys.removeAll { it !in activeIdentitySet }
            }
        }

        private fun handleAutoResetResult(
            ioException: IOException?,
            successCount: Int,
            totalActiveSims: Int,
        ): Boolean {
            if (ioException != null) {
                AppLogger.w(
                    "ResetManager: auto-reset partially failed ($successCount/$totalActiveSims succeeded)",
                )
                return false
            }
            return true
        }

        private suspend fun checkAndPerformResetForSimInternal(
            simInfo: com.example.minutewatcher.core.model.ActiveSimInfo,
            now: Long,
            usage: SimUsage,
        ): Boolean {
            val identity = simInfo.identity.stableId
            var result = false
            if (usage.autoReset) {
                val reset =
                    simUsageResetRepository.resetSimUsageIfDue(
                        identity,
                        usage.autoResetDay,
                        now,
                    )
                if (reset) {
                    AppLogger.d(
                        "Auto reset performed for SIM ${simInfo.slotIndex} ($identity), day: ${usage.autoResetDay}",
                    )
                    result = true
                }
            }
            return result
        }

        companion object {
            private val CHECK_INTERVAL_MS = 1.hours.inWholeMilliseconds
            private const val RESET_TOTAL_TIMEOUT_MS = 30_000L
        }
    }
