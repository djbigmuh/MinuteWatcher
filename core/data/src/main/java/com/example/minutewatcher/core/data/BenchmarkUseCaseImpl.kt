package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.BenchmarkUseCase
import com.example.minutewatcher.core.domain.MigrationFlagsRepository
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimLimitsRepository
import com.example.minutewatcher.core.domain.SimUsageAdjustmentRepository
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.Constants.SimConstants
import kotlinx.coroutines.CancellationException
import java.io.IOException
import javax.inject.Inject

/**
 * Implementation of [BenchmarkUseCase] for populating mock data in UI tests and benchmarks.
 *
 * Safe to call from any coroutine context.
 */
class BenchmarkUseCaseImpl
    @Inject
    constructor(
        private val simIdentityRepository: SimIdentityRepository,
        private val simUsageAdjustmentRepository: SimUsageAdjustmentRepository,
        private val simLimitsRepository: SimLimitsRepository,
        private val appSettingsRepository: AppSettingsRepository,
        private val migrationFlagsRepository: MigrationFlagsRepository,
    ) : BenchmarkUseCase {
        /**
         * Populates DataStore with mock data for a specified number of SIMs.
         *
         * @param simCount Number of SIMs to mock, coerced to [SimConstants.MAX_SUPPORTED_SIM_SLOTS].
         * Safe to call from any coroutine context.
         * @throws IOException if any DataStore update fails.
         * @throws CancellationException if the population process is cancelled.
         */
        override suspend fun populateMockData(simCount: Int) {
            val safeCount = simCount.coerceIn(1, SimConstants.MAX_SUPPORTED_SIM_SLOTS)

            for (i in 0 until safeCount) {
                val identity = simIdentityRepository.getIdentity(i)

                // Set mock operator
                simIdentityRepository.setSimOperator(identity, "Mock Operator ${i + 1}")

                // Set mock limits
                simLimitsRepository.updateSimLimits(
                    identity = identity,
                    minutesLimit = 500 * (i + 1),
                    trafficLimitMb = 5000 * (i + 1),
                    rolloverEnabled = i == 1,
                )

                // Set mock usage
                simUsageAdjustmentRepository.updateSimStats(
                    identity = identity,
                    minutesUsed = 100 * (i + 1),
                    trafficUsedMb = Constants.MB_PER_GB * (i + 1),
                    manualAdjustment = 10 * (i + 1),
                )
            }

            // Prevent onboarding and permission dialogs during benchmarks
            // Force English locale to ensure stable UI tests
            appSettingsRepository.updateAppSettings {
                it.copy(
                    onboardingCompleted = true,
                    notificationPermissionRequested = true,
                    wasPhonePermissionRequestedBefore = true,
                    language = "en",
                    benchmarkSimCount = safeCount,
                )
            }

            // Prevent background migrations during benchmarks
            migrationFlagsRepository.setMigrationV1Completed(completed = true)
            migrationFlagsRepository.setMigrationIdentityStabilityCompleted(completed = true)
        }
    }
