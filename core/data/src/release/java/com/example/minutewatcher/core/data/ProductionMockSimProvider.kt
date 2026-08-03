package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.model.ActiveSimInfo
import javax.inject.Inject

class ProductionMockSimProvider @Inject constructor() : MockSimProvider {
    override fun getMockSims(benchmarkSimCount: Int): List<ActiveSimInfo>? = null
}
