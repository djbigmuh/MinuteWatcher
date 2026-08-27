package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.domain.AutoResetUseCase
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.SimUsageResetRepository
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.SimUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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
        private val lastResetCheck = HashMap<String, Long>()

        /**
         * Checks and performs automatic resets for all active SIMs.
         *
         * @param shouldNotify True if a data change notification should be broadcasted after reset.
         * Safe to call from any coroutine context.
         * @throws java.io.IOException if DataStore operations fail.
         * @throws kotlinx.coroutines.CancellationException if the reset process is cancelled.
         */
        override suspend fun performAutoReset(shouldNotify: Boolean) {
            val now = timeProvider.now()
            val nowElapsed = timeProvider.elapsedRealtime()

            val activeSims =
                simIdentityRepository.observeActiveSims().firstWithTimeout()
                    ?: throw java.io.IOException("Timeout observing active SIMs during auto-reset check")

            if (activeSims.isEmpty()) {
                AppLogger.w("ResetManager: No active SIMs detected during auto-reset check.")
            }

            val resetIdentities = mutableSetOf<String>()
            var ioException: java.io.IOException? = null
            var successCount = 0

            for (simInfo in activeSims) {
                try {
                    if (processSingleSimReset(simInfo, now, nowElapsed, resetIdentities)) {
                        successCount++
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: java.io.IOException) {
                    AppLogger.e("ResetManager: reset failed for SIM ${simInfo.identity.stableId}, continuing", e)
                    ioException = e
                } catch (e: Exception) {
                    AppLogger.e("ResetManager: Unexpected error resetting SIM ${simInfo.identity.stableId}", e)
                }
            }

            cleanupStaleCheckTimestamps(activeSims)

            if (resetIdentities.isNotEmpty()) {
                resetIdentities.forEach { identity -> trafficTracker.resetBaseline(identity) }
                if (shouldNotify) dataChangeNotifier.notifyDataChanged()
            }

            handleAutoResetResult(ioException, successCount, activeSims.size)
        }

        private suspend fun processSingleSimReset(
            simInfo: com.example.minutewatcher.core.model.ActiveSimInfo,
            now: Long,
            nowElapsed: Long,
            resetIdentities: MutableSet<String>,
        ): Boolean {
            val identity = simInfo.identity.stableId
            val resetPerformed =
                resetMutex.withLock {
                    val usage =
                        withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                            simUsageRepository.getSimUsage(identity).first()
                        }

                    if (usage == null) {
                        AppLogger.w("ResetManager: Timeout reading usage for SIM $identity, skipping reset check.")
                        return@withLock false
                    }

                    checkAndPerformResetForSimInternal(simInfo, now, nowElapsed, usage)
                }

            if (resetPerformed) {
                resetIdentities.add(identity)
            }
            return true
        }

        private suspend fun cleanupStaleCheckTimestamps(
            activeSims: List<com.example.minutewatcher.core.model.ActiveSimInfo>,
        ) {
            resetMutex.withLock {
                val activeIdentitySet = activeSims.map { it.identity.stableId }.toSet()
                lastResetCheck.keys.removeAll { it !in activeIdentitySet }
            }
        }

        private fun handleAutoResetResult(
            ioException: java.io.IOException?,
            successCount: Int,
            totalActiveSims: Int,
        ) {
            if (ioException != null) {
                if (successCount == 0 && totalActiveSims > 0) {
                    throw ioException
                } else {
                    AppLogger.w(
                        "ResetManager: auto-reset partially failed, but at least one SIM " +
                            "succeeded. Retrying via worker is skipped.",
                    )
                }
            }
        }

        private suspend fun checkAndPerformResetForSimInternal(
            simInfo: com.example.minutewatcher.core.model.ActiveSimInfo,
            now: Long,
            nowElapsed: Long,
            usage: SimUsage,
        ): Boolean {
            val identity = simInfo.identity.stableId

            // Skip if we checked this identity recently (e.g. within the last hour)
            // Access to lastResetCheck is protected by resetMutex in the caller.
            val lastCheck = lastResetCheck[identity]
            if (lastCheck != null && nowElapsed - lastCheck < CHECK_INTERVAL_MS) {
                return false
            }
            lastResetCheck[identity] = nowElapsed

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
        }
    }
