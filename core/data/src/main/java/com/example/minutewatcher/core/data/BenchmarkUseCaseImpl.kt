package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.BenchmarkUseCase
import com.example.minutewatcher.core.domain.MigrationFlagsRepository
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimLimitsRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.model.SimConstants
import javax.inject.Inject

class BenchmarkUseCaseImpl
    @Inject
    constructor(
        private val simIdentityRepository: SimIdentityRepository,
        private val simUsageRepository: SimUsageRepository,
        private val simLimitsRepository: SimLimitsRepository,
        private val appSettingsRepository: AppSettingsRepository,
        private val migrationFlagsRepository: MigrationFlagsRepository,
    ) : BenchmarkUseCase {
        override suspend fun populateMockData(simCount: Int) {
            val safeCount = simCount.coerceIn(1, SimConstants.MAX_SUPPORTED_SIM_SLOTS)

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
                    rolloverEnabled = i == 1,
                )

                // Set mock usage
                simUsageRepository.updateSimStats(
                    identity = identity,
                    minutesUsed = 100 * (i + 1),
                    trafficUsed = 1024f * (i + 1),
                    manualAdjustment = 10 * (i + 1),
                )
            }

            // Prevent onboarding and permission dialogs during benchmarks
            // Force English locale to ensure stable UI tests
            appSettingsRepository.updateAppSettings {
                it.copy(
                    onboardingCompleted = true,
                    notificationPermissionRequested = true,
                    phonePermissionRequested = true,
                    language = "en",
                    benchmarkSimCount = safeCount,
                )
            }

            // Prevent background migrations during benchmarks
            migrationFlagsRepository.setMigrationV1Completed(true)
            migrationFlagsRepository.setMigrationIdentityStabilityCompleted(true)
        }
    }
