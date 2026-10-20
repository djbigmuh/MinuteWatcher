package host.serv.djbigmuh.minutewatcher.core.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import host.serv.djbigmuh.minutewatcher.core.android.util.OperatorResolver
import host.serv.djbigmuh.minutewatcher.core.common.util.PermissionManager
import host.serv.djbigmuh.minutewatcher.core.common.util.firstWithTimeout
import host.serv.djbigmuh.minutewatcher.core.data.util.SimIdentityUtils
import host.serv.djbigmuh.minutewatcher.core.domain.InvalidSlotException
import host.serv.djbigmuh.minutewatcher.core.domain.SimIdentityRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimLimitsRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimUsageAdjustmentRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimUsageRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimUsageResetRepository
import host.serv.djbigmuh.minutewatcher.core.domain.UsageUseCase
import host.serv.djbigmuh.minutewatcher.core.model.Constants
import host.serv.djbigmuh.minutewatcher.core.model.Constants.SimConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [UsageUseCase] providing business logic for usage management.
 *
 * Safe to call from any coroutine context.
 */
@Singleton
class UsageUseCaseImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val simIdentityRepository: SimIdentityRepository,
        private val simUsageRepository: SimUsageRepository,
        private val simUsageAdjustmentRepository: SimUsageAdjustmentRepository,
        private val simLimitsRepository: SimLimitsRepository,
        private val simUsageResetRepository: SimUsageResetRepository,
        private val trafficTracker: TrafficTracker,
        private val permissionManager: PermissionManager,
    ) : UsageUseCase {
        private val adjustmentMutex = Mutex()

        /**
         * Refreshes carrier names and MCC/MNC codes for all active SIMs.
         *
         * Safe to call from any coroutine context.
         * @throws java.io.IOException if DataStore write fails.
         * @throws CancellationException if the process is cancelled.
         */
        override suspend fun refreshOperatorInfo() {
            val activeList = permissionManager.getActiveSubscriptions()

            for (info in activeList) {
                val slot = info.simSlotIndex
                if (slot in SimConstants.SUPPORTED_SLOT_INDICES) {
                    val identity = simIdentityRepository.getIdentity(slot)
                    val displayName = info.displayName?.toString().orEmpty()
                    val carrierName = info.carrierName?.toString().orEmpty()

                    val candidates = SimIdentityUtils.mccMncCandidates(info)

                    val resolution =
                        OperatorResolver.resolve(
                            context,
                            displayName.ifEmpty { carrierName },
                            candidates,
                            slot,
                        )
                    simIdentityRepository.setSimOperator(identity, resolution.displayName, resolution.brandKey)
                }
            }
        }

        /**
         * Adjusts manual minutes correction to match remaining minutes for current billing period.
         * Used for one-time balance synchronization following events that do not change the tariff plan
         * (e.g. minute purchases/sales, bonuses, SIM usage in another device).
         * Permanent plan changes must be made via limits instead of adjustments.
         * See [host.serv.djbigmuh.minutewatcher.core.model.SimUsage.manualAdjustment].
         *
         * @param slot SIM slot index.
         * @param remainingMinutes User-specified remaining minutes.
         * Safe to call from any coroutine context.
         * @throws java.io.IOException if DataStore operations fail or read times out.
         * @throws InvalidSlotException if slot is invalid.
         * @throws CancellationException if cancelled.
         */
        override suspend fun adjustMinutes(
            slot: Int,
            remainingMinutes: Int,
        ) {
            val identity = simIdentityRepository.getIdentity(slot)
            adjustmentMutex.withLock {
                val limit =
                    simLimitsRepository.getMonthlyLimit(identity).firstWithTimeout()
                        ?: throw IOException("Timeout reading monthly limit for $identity")
                val usage =
                    simUsageRepository.getSimUsage(identity).firstWithTimeout()
                        ?: throw IOException("Timeout reading current usage for $identity")
                val used = usage.minutesUsed

                val safeRemaining = remainingMinutes.coerceIn(0, Constants.MAX_MINUTES_LIMIT)
                val adj = (safeRemaining - limit).toLong() + used.toLong()
                val safeAdj =
                    adj
                        .coerceIn(
                            -Constants.MAX_MINUTES_LIMIT.toLong(),
                            Constants.MAX_MINUTES_LIMIT.toLong(),
                        ).toInt()
                simUsageAdjustmentRepository.setManualAdjustment(identity, safeAdj)
            }
        }

        /**
         * Adjusts manual traffic correction in GB to match remaining traffic for current billing period.
         * Used for one-time balance synchronization following events that do not change the tariff plan
         * (e.g. traffic purchases/sales, bonuses, SIM usage in another device).
         * Permanent plan changes must be made via limits instead of adjustments.
         * See [host.serv.djbigmuh.minutewatcher.core.model.SimUsage.manualAdjustment].
         *
         * @param slot SIM slot index.
         * @param remainingGb User-specified remaining traffic in GB.
         * Safe to call from any coroutine context.
         * @throws IOException if DataStore operations fail or read times out.
         * @throws host.serv.djbigmuh.minutewatcher.core.domain.InvalidSlotException if slot is invalid.
         * @throws CancellationException if cancelled.
         */
        override suspend fun adjustTraffic(
            slot: Int,
            remainingGb: Float,
        ) {
            val identity = simIdentityRepository.getIdentity(slot)
            adjustmentMutex.withLock {
                val limitMb =
                    simLimitsRepository.getTrafficLimit(identity).firstWithTimeout()
                        ?: throw IOException("Timeout reading traffic limit for $identity")
                val usage =
                    simUsageRepository.getSimUsage(identity).firstWithTimeout()
                        ?: throw IOException("Timeout reading current traffic usage for $identity")
                val usedMb = usage.trafficUsedMb

                val safeRemainingGb =
                    remainingGb.toDouble().coerceIn(0.0, Constants.MAX_TRAFFIC_LIMIT_GB.toDouble())
                val mbPerGb = Constants.MB_PER_GB.toDouble()
                val limitGb = limitMb.toDouble() / mbPerGb
                val usedGb = usedMb.toDouble() / mbPerGb
                val adj = safeRemainingGb - (limitGb - usedGb)
                val safeAdj =
                    adj.coerceIn(
                        -Constants.MAX_TRAFFIC_LIMIT_GB.toDouble(),
                        Constants.MAX_TRAFFIC_LIMIT_GB.toDouble(),
                    )
                simUsageAdjustmentRepository.setTrafficAdjustment(identity, safeAdj.toFloat())
            }
        }

        /**
         * Resets all usage counters and baselines for the specified SIM slot.
         *
         * Safe to call from any coroutine context.
         * @throws java.io.IOException if DataStore operations fail.
         * @throws host.serv.djbigmuh.minutewatcher.core.domain.InvalidSlotException if slot is invalid.
         * @throws kotlinx.coroutines.CancellationException if cancelled.
         */
        override suspend fun resetCounters(slot: Int) {
            val identity = simIdentityRepository.getIdentity(slot)
            simUsageResetRepository.resetSimUsageManual(identity)
            trafficTracker.resetBaseline(identity)
        }
    }
