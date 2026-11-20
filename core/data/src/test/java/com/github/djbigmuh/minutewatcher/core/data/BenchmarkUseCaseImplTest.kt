package com.github.djbigmuh.minutewatcher.core.data

import com.github.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import com.github.djbigmuh.minutewatcher.core.domain.MigrationFlagsRepository
import com.github.djbigmuh.minutewatcher.core.domain.SimIdentityRepository
import com.github.djbigmuh.minutewatcher.core.domain.SimLimitsRepository
import com.github.djbigmuh.minutewatcher.core.domain.SimUsageAdjustmentRepository
import com.github.djbigmuh.minutewatcher.core.model.ActiveSimInfo
import com.github.djbigmuh.minutewatcher.core.model.Constants
import com.github.djbigmuh.minutewatcher.core.model.IdentitySource
import com.github.djbigmuh.minutewatcher.core.model.SimIdentity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class BenchmarkUseCaseImplTest {
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simUsageAdjustmentRepository: SimUsageAdjustmentRepository = mock()
    private val simLimitsRepository: SimLimitsRepository = mock()
    private val appSettingsRepository: AppSettingsRepository = mock()
    private val migrationFlagsRepository: MigrationFlagsRepository = mock()

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var useCase: BenchmarkUseCaseImpl

    @Before
    fun setup() {
        useCase =
            BenchmarkUseCaseImpl(
                simIdentityRepository = simIdentityRepository,
                simUsageAdjustmentRepository = simUsageAdjustmentRepository,
                simLimitsRepository = simLimitsRepository,
                appSettingsRepository = appSettingsRepository,
                migrationFlagsRepository = migrationFlagsRepository,
            )
    }

    @Test
    fun `populateMockData saves app settings before waiting for active SIMs`() =
        testScope.runTest {
            val activeSimsFlow = MutableSharedFlow<List<ActiveSimInfo>>(replay = 1)
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(activeSimsFlow)

            var updateSettingsCalled = false
            whenever(appSettingsRepository.updateAppSettings(any())).thenAnswer {
                updateSettingsCalled = true
            }

            val job = launch { useCase.populateMockData(2) }
            testScheduler.runCurrent()

            // Verification: updateAppSettings and migration flags called before activeSimsFlow emitted
            assertTrue(
                "appSettingsRepository.updateAppSettings should be called before SIMs emit",
                updateSettingsCalled,
            )
            verify(migrationFlagsRepository).setMigrationV1Completed(true)
            verify(migrationFlagsRepository).setMigrationIdentityStabilityCompleted(true)

            // Now emit SIMs to complete
            val sim1 =
                ActiveSimInfo(
                    SimIdentity("id_1", 1, 0, IdentitySource.ICCID, true),
                    0,
                    1,
                    "icc_1",
                    "SIM 1",
                    "C1",
                    true,
                    true,
                )
            activeSimsFlow.emit(listOf(sim1))
            testScheduler.advanceUntilIdle()

            withTimeoutOrNull(30.seconds) { job.join() }
                ?: throw AssertionError("Timed out waiting for benchmark job")
        }

    @Test
    fun `populateMockData populates operator limits and stats for each active SIM`() =
        testScope.runTest {
            val sim1 =
                ActiveSimInfo(
                    SimIdentity("id_1", 1, 0, IdentitySource.ICCID, true),
                    0,
                    1,
                    "icc_1",
                    "SIM 1",
                    "C1",
                    true,
                    true,
                )
            val sim2 =
                ActiveSimInfo(
                    SimIdentity("id_2", 2, 1, IdentitySource.ICCID, true),
                    1,
                    2,
                    "icc_2",
                    "SIM 2",
                    "C2",
                    false,
                    false,
                )

            val activeSimsFlow = MutableSharedFlow<List<ActiveSimInfo>>(replay = 1)
            activeSimsFlow.emit(listOf(sim1, sim2))
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(activeSimsFlow)

            useCase.populateMockData(2)

            // Verify SIM 1 (N = 1)
            verify(simIdentityRepository).setSimOperator("id_1", "Mock Operator 1")
            verify(simLimitsRepository).updateSimLimits(
                identity = "id_1",
                minutesLimit = 500,
                trafficLimitMb = 5000,
                rolloverEnabled = false,
            )
            verify(simUsageAdjustmentRepository).updateSimStats(
                identity = "id_1",
                minutesUsed = 100,
                trafficUsedMb = Constants.MB_PER_GB,
                manualAdjustment = 10,
            )

            // Verify SIM 2 (N = 2)
            verify(simIdentityRepository).setSimOperator("id_2", "Mock Operator 2")
            verify(simLimitsRepository).updateSimLimits(
                identity = "id_2",
                minutesLimit = 1000,
                trafficLimitMb = 10000,
                rolloverEnabled = true,
            )
            verify(simUsageAdjustmentRepository).updateSimStats(
                identity = "id_2",
                minutesUsed = 200,
                trafficUsedMb = Constants.MB_PER_GB * 2,
                manualAdjustment = 20,
            )
        }

    @Test
    fun `populateMockData throws IOException when active SIMs flow is empty and times out`() =
        testScope.runTest {
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(emptyFlow())

            try {
                useCase.populateMockData(2)
                fail("Expected IOException on timeout")
            } catch (e: IOException) {
                assertTrue("Exception message should indicate timeout", e.message?.contains("Timed out") == true)
            }
        }
}
