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
import kotlinx.coroutines.flow.MutableStateFlow
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
class UsageCheckWorkerTest {

    private lateinit var context: Context
    private lateinit var simRepository: SimRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var resetManager: ResetManager
    private lateinit var trafficTracker: TrafficTracker
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var updateCoordinator: UpdateCoordinator
    private lateinit var usageStateRepository: UsageStateRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        
        // Grant permission for Robolectric
        val packageName = context.packageName
        val shadowApp = org.robolectric.Shadows.shadowOf(context as android.app.Application)
        shadowApp.grantPermissions(packageName, android.Manifest.permission.READ_PHONE_STATE)

        simRepository = mock()
        settingsRepository = mock()
        resetManager = mock()
        trafficTracker = mock()
        notificationHelper = mock()
        updateCoordinator = mock()
        usageStateRepository = mock()

        whenever(settingsRepository.observeAppSettings()).thenReturn(MutableStateFlow(AppSettings(
            notifyLowMinutes = true,
            notifyLowTraffic = true
        )))
        
        whenever(simRepository.getNotifiedExhaustedMinutes(any())).thenReturn(flowOf(false))
        whenever(simRepository.getNotifiedLowMinutes(any())).thenReturn(flowOf(false))
        whenever(simRepository.getNotifiedExhaustedTraffic(any())).thenReturn(flowOf(false))
        whenever(simRepository.getNotifiedLowTraffic(any())).thenReturn(flowOf(false))
    }

    @Test
    fun `doWork sends notification when minutes are exhausted`() = runTest {
        // Mock a snapshot with exhausted minutes
        val entityMinutes = SemanticEntity(
            id = "sim_0_minutes",
            simSlot = 0,
            operatorName = "MTS",
            packageType = PackageType.MINUTES,
            limit = 100,
            used = 100,
            remaining = 0,
            unit = MeasurementUnit.MINUTES,
            periodStart = 0,
            periodEnd = 0,
            lastUpdateTime = 0,
            sourceType = DataSourceType.SYSTEM
        )
        
        val simSnapshot = SimSnapshot(0, "MTS", true, mapOf(PackageType.MINUTES to entityMinutes))
        val snapshot = GlobalSnapshot(SchemaVersion(1,0,0), System.currentTimeMillis(), listOf(simSnapshot), emptyList())
        
        whenever(usageStateRepository.refresh()).thenReturn(snapshot)
        whenever(simRepository.getIdentity(0)).thenReturn("sim_0")
        whenever(simRepository.getSimUsage("sim_0")).thenReturn(flowOf(SimUsage(
            identity = "sim_0",
            slotIndex = 0,
            subscriptionId = 1,
            operator = "MTS",
            minutesUsed = 100,
            minutesLimit = 100,
            trafficUsed = 0f,
            trafficLimitMb = 0,
            manualAdjustment = 0,
            trafficAdjustment = 0f,
            autoReset = true,
            autoResetDay = 1,
            lastResetDate = 0L
        )))

        val worker = createWorker()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        verify(notificationHelper).sendUsageNotification(any(), any(), eq(0), any())
    }

    private fun createWorker(): UsageCheckWorker {
        return TestListenableWorkerBuilder<UsageCheckWorker>(context)
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ListenableWorker {
                    return UsageCheckWorker(
                        appContext,
                        workerParameters,
                        simRepository,
                        settingsRepository,
                        resetManager,
                        trafficTracker,
                        notificationHelper,
                        updateCoordinator,
                        usageStateRepository
                    )
                }
            })
            .build()
    }
}
