package com.github.djbigmuh.minutewatcher.core.data

import com.github.djbigmuh.minutewatcher.core.common.util.AppLogger
import com.github.djbigmuh.minutewatcher.core.domain.SimUsageResetRepository
import com.github.djbigmuh.minutewatcher.core.model.Constants
import com.github.djbigmuh.minutewatcher.core.model.util.UnitConversionUtils
import kotlinx.coroutines.CancellationException
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
            expectedLastResetDate: Long,
        ): Boolean {
            // Operation must complete without propagating exception for baseline and usage update;
            // failure degrades to a skipped update.
            @Suppress("TooGenericExceptionCaught")
            try {
                if ((lastTotalBytes == -1L) || (currentBytes < lastTotalBytes)) {
                    val applied =
                        simUsageResetRepository.setLastTotalBytes(
                            targetIdentity,
                            currentBytes,
                            expectedLastResetDate,
                            subId = target.dataSubId,
                        )
                    if (applied) {
                        AppLogger.d("Traffic baseline reset for $targetIdentity to $currentBytes")
                    } else {
                        AppLogger.d(
                            "TrafficDeltaCalculator: baseline reset write skipped " +
                                "due to window mismatch for $targetIdentity",
                        )
                    }
                    return applied
                } else {
                    return handleTrafficDelta(
                        targetIdentity,
                        currentBytes,
                        lastTotalBytes,
                        target.slot,
                        target.dataSubId,
                        expectedLastResetDate,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Operation must complete without propagating exception for traffic delta update logging.
                AppLogger.e(
                    "TrafficDeltaCalculator: Critical error updating baseline/usage for $targetIdentity",
                    e,
                )
                throw e
            }
        }

        private suspend fun handleTrafficDelta(
            identity: String,
            currentBytes: Long,
            lastTotalBytes: Long,
            slot: Int,
            dataSubId: Int,
            expectedLastResetDate: Long,
        ): Boolean {
            // Operation must complete without propagating exception for delta handling;
            // failure degrades to a skipped update.
            @Suppress("TooGenericExceptionCaught")
            try {
                val deltaBytes = currentBytes - lastTotalBytes
                if (deltaBytes > 0L) {
                    val deltaMB = UnitConversionUtils.bytesToMb(deltaBytes)
                    if (deltaMB >= Constants.Traffic.MIN_DELTA_MB) {
                        val applied =
                            simUsageResetRepository.incrementTrafficAndSetBaseline(
                                identity,
                                deltaMB,
                                currentBytes,
                                expectedLastResetDate,
                                subId = dataSubId,
                            )
                        if (applied) {
                            AppLogger.d("Added $deltaMB MB to identity $identity (SIM $slot)")
                        } else {
                            AppLogger.d(
                                "TrafficDeltaCalculator: increment traffic write skipped " +
                                    "due to window mismatch for $identity",
                            )
                        }
                        return applied
                    }
                }
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Operation must complete without propagating exception for traffic delta handling logging.
                AppLogger.e(
                    "TrafficDeltaCalculator: Error calculating/persisting delta for $identity",
                    e,
                )
                throw e
            }
        }
    }
