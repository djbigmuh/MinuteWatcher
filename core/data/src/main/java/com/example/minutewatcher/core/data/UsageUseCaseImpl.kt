package com.example.minutewatcher.core.data

import android.content.Context
import com.example.minutewatcher.core.android.util.OperatorResolver
import com.example.minutewatcher.core.android.util.PermissionManager
import com.example.minutewatcher.core.data.util.SimIdentityUtils
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimLimitsRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.UsageUseCase
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.SimConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsageUseCaseImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val simIdentityRepository: SimIdentityRepository,
        private val simUsageRepository: SimUsageRepository,
        private val simLimitsRepository: SimLimitsRepository,
        private val permissionManager: PermissionManager,
    ) : UsageUseCase {
        override suspend fun refreshOperatorInfo() {
            try {
                val activeList = permissionManager.getActiveSubscriptions()

                for (info in activeList) {
                    val slot = info.simSlotIndex
                    if (slot in SimConstants.SUPPORTED_SLOT_INDICES) {
                        val identity = simIdentityRepository.getIdentity(slot)
                        val displayName = info.displayName?.toString() ?: ""
                        val carrierName = info.carrierName?.toString() ?: ""

                        val mccMnc = SimIdentityUtils.getMccMnc(info)

                        val opName = OperatorResolver.resolve(context, displayName.ifEmpty { carrierName }, mccMnc, slot)
                        simIdentityRepository.setSimOperator(identity, opName)
                        simIdentityRepository.setSimCode(identity, mccMnc)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                com.example.minutewatcher.core.common.util.AppLogger
                    .e("Error refreshing operator info", e)
            }
        }

        override suspend fun adjustMinutes(
            slot: Int,
            remainingMinutes: Int,
        ) {
            val identity = simIdentityRepository.getIdentity(slot)
            val limit = simLimitsRepository.getMonthlyLimit(identity).first()
            val used = simUsageRepository.getMinutesUsed(identity).first()

            val safeRemaining = remainingMinutes.coerceIn(0, Constants.MAX_MINUTES_LIMIT)
            val adj = (safeRemaining - limit) + used
            simUsageRepository.setManualAdjustment(identity, adj)
        }

        override suspend fun adjustTraffic(
            slot: Int,
            remainingGb: Float,
        ) {
            val identity = simIdentityRepository.getIdentity(slot)
            val limitMb = simLimitsRepository.getTrafficLimit(identity).first()
            val usedMb = simUsageRepository.getTrafficUsed(identity).first()

            val safeRemainingGb = remainingGb.coerceIn(0f, Constants.MAX_TRAFFIC_LIMIT_GB)
            val limitGb = limitMb / Constants.MB_PER_GB
            val usedGb = usedMb / Constants.MB_PER_GB
            val adj = safeRemainingGb - (limitGb - usedGb)
            simUsageRepository.setTrafficAdjustment(identity, adj)
        }
    }
