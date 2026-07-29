package com.example.minutewatcher.core.domain

import android.content.Context
import android.os.Build
import android.telephony.SubscriptionInfo
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.SimConstants
import com.example.minutewatcher.core.android.util.OperatorResolver
import com.example.minutewatcher.core.android.util.PermissionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsageUseCase
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val simIdentityRepository: SimIdentityRepository,
        private val simUsageRepository: SimUsageRepository,
        private val simLimitsRepository: SimLimitsRepository,
        private val permissionManager: PermissionManager,
    ) {
        suspend fun refreshOperatorInfo() {
            try {
                val activeList = permissionManager.getActiveSubscriptions()

                for (info in activeList) {
                    val slot = info.simSlotIndex
                    if (slot in SimConstants.SUPPORTED_SLOT_INDICES) {
                        val identity = simIdentityRepository.getIdentity(slot)
                        val displayName = info.displayName?.toString() ?: ""
                        val carrierName = info.carrierName?.toString() ?: ""

                        val mcc = getMcc(info)
                        val mnc = getMnc(info)
                        val mccMnc = if (mcc.isNotEmpty() && mnc.isNotEmpty()) "$mcc$mnc" else ""

                        val opName = OperatorResolver.resolve(context, displayName.ifEmpty { carrierName }, mccMnc, slot)
                        simIdentityRepository.setSimOperator(identity, opName)
                        simIdentityRepository.setSimCode(identity, mccMnc)
                    }
                }
            } catch (e: Exception) {
                com.example.minutewatcher.core.common.util.AppLogger
                    .e("Error refreshing operator info", e)
            }
        }

        private fun getMcc(info: SubscriptionInfo): String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.mccString ?: ""
            } else {
                @Suppress("DEPRECATION")
                info.mcc.toString()
            }

        private fun getMnc(info: SubscriptionInfo): String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.mncString ?: ""
            } else {
                @Suppress("DEPRECATION")
                info.mnc.toString()
            }

        suspend fun adjustMinutes(
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

        suspend fun adjustTraffic(
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

