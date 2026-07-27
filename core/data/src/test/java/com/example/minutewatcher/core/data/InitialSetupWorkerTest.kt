package com.example.minutewatcher.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.minutewatcher.core.domain.SettingsRepository
import com.example.minutewatcher.core.domain.SimRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.model.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InitialSetupWorkerTest {

    private lateinit var context: Context
    private lateinit var callTracker: CallTracker
    private lateinit var trafficTracker: TrafficTracker
    private lateinit var simRepository: SimRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var usageStateRepository: UsageStateRepository
    private lateinit var migrationService: IdentityMigrationService
    private lateinit var updateCoordinator: UpdateCoordinator

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        callTracker = mock()
        trafficTracker = mock()
        simRepository = mock()
        settingsRepository = mock()
        usageStateRepository = mock()
        migrationService = mock()
        updateCoordinator = mock()

        whenever(settingsRepository.isMigrationV1Completed()).thenReturn(flowOf(false))
        whenever(simRepository.observeActiveSims()).thenReturn(flowOf(emptyList()))
    }

    @Test
    fun `doWork marks migration as completed when no legacy data exists`() = runTest {
        whenever(migrationService.hasLegacyData()).thenReturn(false)
        whenever(usageStateRepository.refresh()).thenReturn(mock())

        val worker = createWorker()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        verify(settingsRepository).setMigrationV1Completed(true)
        verify(settingsRepository).setMigrationV1Skipped(MigrationSkipReason.NO_LEGACY_DATA)
        verify(usageStateRepository).refresh()
    }

    @Test
    fun `doWork skips migration after retries when no permission`() = runTest {
        whenever(migrationService.hasLegacyData()).thenReturn(true)
        whenever(usageStateRepository.refresh()).thenReturn(mock())
        
        val worker = createWorker(runAttemptCount = 6)
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        verify(settingsRepository).setMigrationV1Completed(true)
        verify(settingsRepository).setMigrationV1Skipped(MigrationSkipReason.NO_PERMISSION)
    }

    @Test
    fun `doWork resets traffic baseline for active SIMs`() = runTest {
        whenever(settingsRepository.isMigrationV1Completed()).thenReturn(flowOf(true))
        
        val activeSim = ActiveSimInfo(
            identity = SimIdentity("id0", 1, 0, IdentitySource.SUBSCRIPTION_ID),
            slotIndex = 0,
            subscriptionId = 1,
            displayName = "MTS",
            carrierName = "MTS",
            mccMnc = "25001",
            isDefaultData = true,
            isDefaultVoice = true
        )
        
        whenever(simRepository.observeActiveSims()).thenReturn(flowOf(listOf(activeSim)))
        whenever(usageStateRepository.refresh()).thenReturn(mock())

        val worker = createWorker()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        verify(trafficTracker).resetBaseline("id0")
        verify(updateCoordinator).notifyDataChanged(eq(true), eq(true))
    }

    private fun createWorker(runAttemptCount: Int = 0): InitialSetupWorker {
        return TestListenableWorkerBuilder<InitialSetupWorker>(context)
            .setRunAttemptCount(runAttemptCount)
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ListenableWorker {
                    return InitialSetupWorker(
                        appContext,
                        workerParameters,
                        callTracker,
                        trafficTracker,
                        simRepository,
                        settingsRepository,
                        usageStateRepository,
                        migrationService,
                        updateCoordinator
                    )
                }
            })
            .build()
    }
}
