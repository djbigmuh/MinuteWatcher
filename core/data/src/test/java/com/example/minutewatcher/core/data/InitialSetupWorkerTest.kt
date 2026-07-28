package com.example.minutewatcher.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.minutewatcher.core.domain.SettingsRepository
import com.example.minutewatcher.core.domain.SimRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.UsageUseCase
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.IdentitySource
import com.example.minutewatcher.core.model.MigrationSkipReason
import com.example.minutewatcher.core.model.SimIdentity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InitialSetupWorkerTest {
    private lateinit var context: Context
    private lateinit var callTracker: CallTracker
    private lateinit var trafficTracker: TrafficTracker
    private lateinit var simRepository: SimRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var usageStateRepository: UsageStateRepository
    private lateinit var usageUseCase: UsageUseCase
    private lateinit var migrationService: IdentityMigrationService
    private lateinit var updateCoordinator: UpdateCoordinator
    private lateinit var workScheduler: WorkScheduler

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        callTracker = mock()
        trafficTracker = mock()
        simRepository = mock()
        settingsRepository = mock()
        usageStateRepository = mock()
        usageUseCase = mock()
        migrationService = mock()
        updateCoordinator = mock()
        workScheduler = mock()

        whenever(settingsRepository.isMigrationV1Completed()).thenReturn(flowOf(true))
        whenever(settingsRepository.isMigrationIdentityStabilityCompleted()).thenReturn(flowOf(true))
        whenever(simRepository.observeActiveSims()).thenReturn(flowOf(emptyList()))
    }

    @Test
    fun `doWork marks migration as completed when no legacy data exists`() =
        runTest {
            whenever(settingsRepository.isMigrationV1Completed()).thenReturn(flowOf(false))
            whenever(settingsRepository.isMigrationIdentityStabilityCompleted()).thenReturn(flowOf(false))
            whenever(migrationService.hasLegacyData()).thenReturn(false)
            whenever(usageStateRepository.refresh()).thenReturn(mock())

            val worker = createWorker()
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(settingsRepository).setMigrationV1Completed(true)
            verify(settingsRepository).setMigrationV1Skipped(MigrationSkipReason.NO_LEGACY_DATA)
            verify(settingsRepository).setMigrationIdentityStabilityCompleted(true)
            verify(usageStateRepository).refresh()
        }

    @Test
    fun `doWork does not mark migration as completed when permission is missing but legacy data exists`() =
        runTest {
            whenever(settingsRepository.isMigrationV1Completed()).thenReturn(flowOf(false))
            whenever(settingsRepository.isMigrationIdentityStabilityCompleted()).thenReturn(flowOf(false))
            whenever(migrationService.hasLegacyData()).thenReturn(true)
            whenever(usageStateRepository.refresh()).thenReturn(mock())

            val worker = createWorker()
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            // Should NOT be called
            verify(settingsRepository, org.mockito.kotlin.never()).setMigrationV1Completed(true)
        }

    @Test
    fun `doWork does not mark migration as completed when no active SIMs found but legacy data exists`() =
        runTest {
            // Grant permission
            val app = ApplicationProvider.getApplicationContext<android.app.Application>()
            org.robolectric.Shadows
                .shadowOf(app)
                .grantPermissions(android.Manifest.permission.READ_PHONE_STATE)

            whenever(settingsRepository.isMigrationV1Completed()).thenReturn(flowOf(false))
            whenever(settingsRepository.isMigrationIdentityStabilityCompleted()).thenReturn(flowOf(false))
            whenever(migrationService.hasLegacyData()).thenReturn(true)
            whenever(simRepository.observeActiveSims()).thenReturn(flowOf(emptyList()))
            whenever(usageStateRepository.refresh()).thenReturn(mock())

            val worker = createWorker()
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            // Should NOT be called
            verify(settingsRepository, org.mockito.kotlin.never()).setMigrationV1Completed(true)
        }

    @Test
    fun `doWork resets traffic baseline for active SIMs`() =
        runTest {
            whenever(settingsRepository.isMigrationV1Completed()).thenReturn(flowOf(true))

            val activeSim =
                ActiveSimInfo(
                    identity = SimIdentity("id0", 1, 0, IdentitySource.SUBSCRIPTION_ID),
                    slotIndex = 0,
                    subscriptionId = 1,
                    displayName = "MTS",
                    carrierName = "MTS",
                    mccMnc = "25001",
                    isDefaultData = true,
                    isDefaultVoice = true,
                )

            whenever(simRepository.observeActiveSims()).thenReturn(flowOf(listOf(activeSim)))
            whenever(usageStateRepository.refresh()).thenReturn(mock())

            val worker = createWorker()
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(trafficTracker).resetBaseline("id0")
            verify(updateCoordinator).notifyDataChanged(eq(true), eq(true))
        }

    @Test
    fun `doWork resets baseline for multiple active SIMs`() =
        runTest {
            whenever(settingsRepository.isMigrationV1Completed()).thenReturn(flowOf(true))

            val sim0 = ActiveSimInfo(SimIdentity("id0", 1, 0, IdentitySource.SUBSCRIPTION_ID), 0, 1, "MTS", "MTS", "25001", true, true)
            val sim1 =
                ActiveSimInfo(
                    SimIdentity("id1", 2, 1, IdentitySource.SUBSCRIPTION_ID),
                    1,
                    2,
                    "Beeline",
                    "Beeline",
                    "25099",
                    false,
                    false,
                )

            whenever(simRepository.observeActiveSims()).thenReturn(flowOf(listOf(sim0, sim1)))
            whenever(usageStateRepository.refresh()).thenReturn(mock())

            val worker = createWorker()
            worker.doWork()

            verify(trafficTracker).resetBaseline("id0")
            verify(trafficTracker).resetBaseline("id1")
        }

    @Test
    fun `doWork schedules periodic usage check when permission is granted`() =
        runTest {
            // Grant permission in Robolectric
            val app = ApplicationProvider.getApplicationContext<android.app.Application>()
            org.robolectric.Shadows
                .shadowOf(app)
                .grantPermissions(android.Manifest.permission.READ_PHONE_STATE)

            whenever(settingsRepository.isMigrationV1Completed()).thenReturn(flowOf(true))
            whenever(simRepository.observeActiveSims()).thenReturn(flowOf(emptyList()))
            whenever(usageStateRepository.refresh()).thenReturn(mock())

            val worker = createWorker()
            worker.doWork()

            verify(workScheduler).schedulePeriodicUsageCheck()
        }

    private fun createWorker(runAttemptCount: Int = 0): InitialSetupWorker =
        TestListenableWorkerBuilder<InitialSetupWorker>(context)
            .setRunAttemptCount(runAttemptCount)
            .setWorkerFactory(
                object : androidx.work.WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker =
                        InitialSetupWorker(
                            appContext,
                            workerParameters,
                            callTracker,
                            trafficTracker,
                            simRepository,
                            settingsRepository,
                            usageStateRepository,
                            usageUseCase,
                            migrationService,
                            updateCoordinator,
                            workScheduler,
                        )
                },
            ).build()
}
