package host.serv.djbigmuh.minutewatcher.core.data

import host.serv.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import host.serv.djbigmuh.minutewatcher.core.domain.BenchmarkUseCase
import host.serv.djbigmuh.minutewatcher.core.domain.MigrationFlagsRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimIdentityRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimLimitsRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimUsageAdjustmentRepository
import host.serv.djbigmuh.minutewatcher.core.model.Constants
import host.serv.djbigmuh.minutewatcher.core.model.Constants.SimConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

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
     * @throws IOException if any DataStore update fails or waiting for active SIMs times out.
     * @throws CancellationException if the population process is cancelled.
     */
    override suspend fun populateMockData(simCount: Int) {
        val safeCount = simCount.coerceIn(1, SimConstants.MAX_SUPPORTED_SIM_SLOTS)

        // 1. Set app settings flags and migration completion flags first
        appSettingsRepository.updateAppSettings {
            it.copy(
                onboardingCompleted = true,
                notificationPermissionRequested = true,
                wasPhonePermissionRequestedBefore = true,
                language = "en",
                benchmarkSimCount = safeCount,
            )
        }
        migrationFlagsRepository.setMigrationV1Completed(completed = true)
        migrationFlagsRepository.setMigrationIdentityStabilityCompleted(completed = true)

        // 2. Wait for active SIMs to appear with timeout
        val timeout = Constants.Flow.INITIAL_SETUP_RETRY_TIMEOUT_MS.milliseconds
        val activeSims =
            withTimeoutOrNull(timeout) {
                simIdentityRepository.observeActiveSims().firstOrNull { it.isNotEmpty() }
            }

        if (activeSims.isNullOrEmpty()) {
            throw IOException("Timed out waiting for active SIMs in benchmark populate")
        }

        // 3. Populate mock data for each active SIM
        activeSims.forEachIndexed { index, sim ->
            val n = index + 1
            val identity = sim.identity.stableId

            simIdentityRepository.setSimOperator(identity, "Mock Operator $n")

            simLimitsRepository.updateSimLimits(
                identity = identity,
                minutesLimit = 500 * n,
                trafficLimitMb = 5000 * n,
                rolloverEnabled = n == 2,
            )

            simUsageAdjustmentRepository.updateSimStats(
                identity = identity,
                minutesUsed = 100 * n,
                trafficUsedMb = Constants.MB_PER_GB * n,
                manualAdjustment = 10 * n,
            )
        }
    }
}
