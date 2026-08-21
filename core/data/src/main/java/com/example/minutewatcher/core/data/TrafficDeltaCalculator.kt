package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.SimUsageResetRepository
import com.example.minutewatcher.core.model.Constants
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Calculates traffic delta and updates usage.
 *
 * Safe to call from any coroutine context.
 */
@Singleton
class TrafficDeltaCalculator
    @Inject
    constructor(
        private val simUsageResetRepository: SimUsageResetRepository,
    ) {
        suspend fun updateTrafficBaselineAndUsage(
            targetIdentity: String,
            currentBytes: Long,
            lastTotalBytes: Long,
            target: TargetInfo,
        ) {
            if ((lastTotalBytes == -1L) || (currentBytes < lastTotalBytes)) {
                simUsageResetRepository.setLastTotalBytes(targetIdentity, currentBytes)
                AppLogger.d("Traffic baseline reset for $targetIdentity to $currentBytes")
            } else {
                handleTrafficDelta(targetIdentity, currentBytes, lastTotalBytes, target.slot)
            }
        }

        private suspend fun handleTrafficDelta(
            identity: String,
            currentBytes: Long,
            lastTotalBytes: Long,
            slot: Int,
        ) {
            val deltaBytes = currentBytes - lastTotalBytes
            if (deltaBytes > 0L) {
                val deltaMB = (deltaBytes.toDouble() / Constants.BYTES_PER_MB).toFloat()
                if (deltaMB >= Constants.Traffic.MIN_DELTA_MB) {
                    simUsageResetRepository.incrementTrafficAndSetBaseline(
                        identity,
                        deltaMB,
                        currentBytes,
                    )
                    AppLogger.d("Added $deltaMB MB to identity $identity (SIM $slot)")
                }
            }
        }
    }
