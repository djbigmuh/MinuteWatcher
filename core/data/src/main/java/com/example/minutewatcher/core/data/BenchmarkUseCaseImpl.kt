package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.domain.BenchmarkUseCase
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimLimitsRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import javax.inject.Inject

class BenchmarkUseCaseImpl @Inject constructor(
    private val simIdentityRepository: SimIdentityRepository,
    private val simUsageRepository: SimUsageRepository,
    private val simLimitsRepository: SimLimitsRepository,
) : BenchmarkUseCase {

    override suspend fun populateMockData(simCount: Int) {
        val safeCount = simCount.coerceIn(1, 2)
        
        for (i in 0 until safeCount) {
            val identity = simIdentityRepository.getIdentity(i)
            
            // Set mock operator
            simIdentityRepository.setSimOperator(identity, "Mock Operator ${i + 1}")
            simIdentityRepository.setSimCode(identity, "00100${i + 1}")
            
            // Set mock limits
            simLimitsRepository.updateSimLimits(
                identity = identity,
                minutesLimit = 500 * (i + 1),
                trafficLimitMb = 5000 * (i + 1),
                rolloverEnabled = i == 1
            )
            
            // Set mock usage
            simUsageRepository.updateSimStats(
                identity = identity,
                minutesUsed = 100 * (i + 1),
                trafficUsed = 1024f * (i + 1),
                manualAdjustment = 10 * (i + 1)
            )
        }
    }
}
