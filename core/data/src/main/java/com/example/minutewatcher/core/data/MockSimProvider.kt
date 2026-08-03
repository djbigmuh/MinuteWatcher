package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.model.ActiveSimInfo

/**
 * Interface for providing mock SIM data.
 * The production implementation should return null to ensure no mock data is used.
 */
interface MockSimProvider {
    fun getMockSims(benchmarkSimCount: Int): List<ActiveSimInfo>?
}
