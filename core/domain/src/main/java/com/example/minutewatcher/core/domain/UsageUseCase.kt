package com.example.minutewatcher.core.domain

import android.content.Context
import android.os.Build
import android.telephony.SubscriptionInfo
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.util.OperatorResolver
import com.example.minutewatcher.core.model.util.PermissionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsageUseCase
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val simRepository: SimRepository,
        private val permissionManager: PermissionManager,
    ) {
        suspend fun refreshOperatorInfo() {
            try {
                val activeList = permissionManager.getActiveSubscriptions()

                for (info in activeList) {
                    val slot = info.simSlotIndex
                    if (slot in 0..1) {
                        val identity = simRepository.getIdentity(slot)
                        val displayName = info.displayName?.toString() ?: ""
                        val carrierName = info.carrierName?.toString() ?: ""

                        val mcc = getMcc(info)
                        val mnc = getMnc(info)
                        val mccMnc = if (mcc.isNotEmpty() && mnc.isNotEmpty()) "$mcc$mnc" else ""

                        val opName = OperatorResolver.resolve(context, displayName.ifEmpty { carrierName }, mccMnc, slot)
                        simRepository.setSimOperator(identity, opName)
                        simRepository.setSimCode(identity, mccMnc)
                    }
                }
            } catch (e: Exception) {
                com.example.minutewatcher.core.model.util.AppLogger
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
            val identity = simRepository.getIdentity(slot)
            val limit = simRepository.getMonthlyLimit(identity).first()
            val used = simRepository.getMinutesUsed(identity).first()
            val adj = (remainingMinutes - limit) + used
            simRepository.setManualAdjustment(identity, adj)
        }

        suspend fun adjustTraffic(
            slot: Int,
            remainingGb: Float,
        ) {
            val identity = simRepository.getIdentity(slot)
            val limitMb = simRepository.getTrafficLimit(identity).first()
            val usedMb = simRepository.getTrafficUsed(identity).first()
            val limitGb = limitMb / Constants.MB_PER_GB
            val usedGb = usedMb / Constants.MB_PER_GB
            val adj = remainingGb - (limitGb - usedGb)
            simRepository.setTrafficAdjustment(identity, adj)
        }
    }
