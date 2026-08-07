package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.Constants.SimConstants
import com.example.minutewatcher.core.model.IdentitySource
import com.example.minutewatcher.core.model.SimIdentity
import javax.inject.Inject

class DebugMockSimProvider
    @Inject
    constructor() : MockSimProvider {
        override fun getMockSims(benchmarkSimCount: Int): List<ActiveSimInfo>? {
            if (benchmarkSimCount <= 0) return null

            val safeCount = benchmarkSimCount.coerceIn(0, SimConstants.MAX_SUPPORTED_SIM_SLOTS)
            return (0 until safeCount).map { i ->
                val identity =
                    SimIdentity(
                        stableId = "sim_$i",
                        subscriptionId = SimConstants.MOCK_SUB_ID_BASE + i,
                        slotIndex = i,
                        source = IdentitySource.SLOT_FALLBACK,
                    )

                ActiveSimInfo(
                    identity = identity,
                    slotIndex = i,
                    subscriptionId = identity.subscriptionId ?: (SimConstants.MOCK_SUB_ID_BASE + i),
                    displayName = "SIM ${i + 1}",
                    carrierName = "Mock Carrier ${i + 1}",
                    mccMnc = "001001",
                    isDefaultData = i == 0, // DEFAULT_SIM_INDEX = 0
                    isDefaultVoice = i == 0,
                )
            }
        }
    }
