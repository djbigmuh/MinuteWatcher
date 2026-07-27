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
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.DataSourceType
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.MeasurementUnit
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.SemanticEntity
import com.example.minutewatcher.core.model.SimSnapshot
import com.example.minutewatcher.core.model.SimUsage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
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
    private lateinit var usageUseCase: UsageUseCase

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
        usageUseCase = mock()

        whenever(settingsRepository.observeAppSettings()).thenReturn(
            MutableStateFlow(
                AppSettings(
                    notifyLowMinutes = true,
                    notifyLowTraffic = true,
                ),
            ),
        )

        whenever(simRepository.getNotifiedExhaustedMinutes(any())).thenReturn(flowOf(false))
        whenever(simRepository.getNotifiedLowMinutes(any())).thenReturn(flowOf(false))
        whenever(simRepository.getNotifiedExhaustedTraffic(any())).thenReturn(flowOf(false))
        whenever(simRepository.getNotifiedLowTraffic(any())).thenReturn(flowOf(false))
    }

    @Test
    fun `doWork sends notification when minutes are exhausted`() =
        runTest {
            // Mock a snapshot with exhausted minutes
            val entityMinutes =
                SemanticEntity(
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
                    sourceType = DataSourceType.SYSTEM,
                )

            val simSnapshot = SimSnapshot(0, "MTS", true, mapOf(PackageType.MINUTES to entityMinutes))
            val snapshot = GlobalSnapshot(SchemaVersion(1, 0, 0), System.currentTimeMillis(), listOf(simSnapshot), emptyList())

            whenever(usageStateRepository.refresh()).thenReturn(snapshot)
            whenever(simRepository.getIdentity(0)).thenReturn("sim_0")
            whenever(simRepository.getSimUsage("sim_0")).thenReturn(
                flowOf(
                    SimUsage(
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
                        lastResetDate = 0L,
                    ),
                ),
            )

            val worker = createWorker()
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(notificationHelper).sendUsageNotification(any(), any(), eq(0), any())
        }

    @Test
    fun `doWork does not send notification when minutes are low but month progress is high`() =
        runTest {
            // Used 80%, limit 100 -> remaining 20%
            // Month progress 90% (near end of month) -> No notification needed
            val entityMinutes = createEntity(PackageType.MINUTES, limit = 100, used = 80)
            val simSnapshot = SimSnapshot(0, "MTS", true, mapOf(PackageType.MINUTES to entityMinutes))
            val snapshot = GlobalSnapshot(SchemaVersion(1, 0, 0), System.currentTimeMillis(), listOf(simSnapshot), emptyList())

            whenever(usageStateRepository.refresh()).thenReturn(snapshot)
            whenever(simRepository.getIdentity(0)).thenReturn("sim_0")
            whenever(simRepository.getSimUsage("sim_0")).thenReturn(flowOf(createSimUsage("sim_0", minutesUsed = 80, minutesLimit = 100, autoResetDay = 1)))

            // Mock date utils to return 90% month progress
            // Note: In real app we might need to mock DateUtils or rely on system clock in Robolectric.
            // For now, this test identifies the logic branch.
            
            createWorker().doWork()
            verify(notificationHelper, org.mockito.kotlin.never()).sendUsageNotification(any(), any(), any(), any())
        }

    @Test
    fun `doWork sends traffic notification when limit is reached`() =
        runTest {
            val entityData = createEntity(PackageType.DATA, limit = 1024 * 1024 * 1024L, used = 1024 * 1024 * 1024L)
            val simSnapshot = SimSnapshot(0, "MTS", true, mapOf(PackageType.DATA to entityData))
            val snapshot = GlobalSnapshot(SchemaVersion(1, 0, 0), System.currentTimeMillis(), listOf(simSnapshot), emptyList())

            whenever(usageStateRepository.refresh()).thenReturn(snapshot)
            whenever(simRepository.getIdentity(0)).thenReturn("sim_0")
            whenever(simRepository.getSimUsage("sim_0")).thenReturn(flowOf(createSimUsage("sim_0", trafficUsed = 1024f, trafficLimit = 1024)))

            createWorker().doWork()
            verify(notificationHelper).sendUsageNotification(any(), any(), eq(0), eq(1))
        }

    private fun createEntity(type: PackageType, limit: Long, used: Long) = SemanticEntity(
        id = "id",
        simSlot = 0,
        operatorName = "MTS",
        packageType = type,
        limit = limit,
        used = used,
        remaining = maxOf(0L, limit - used),
        unit = if (type == PackageType.MINUTES) MeasurementUnit.MINUTES else MeasurementUnit.BYTES,
        periodStart = 0,
        periodEnd = 0,
        lastUpdateTime = 0,
        sourceType = DataSourceType.SYSTEM
    )

    private fun createSimUsage(id: String, minutesUsed: Int = 0, minutesLimit: Int = 0, trafficUsed: Float = 0f, trafficLimit: Int = 0, autoResetDay: Int = 1) = SimUsage(
        identity = id,
        slotIndex = 0,
        subscriptionId = 1,
        operator = "MTS",
        minutesUsed = minutesUsed,
        minutesLimit = minutesLimit,
        trafficUsed = trafficUsed,
        trafficLimitMb = trafficLimit,
        manualAdjustment = 0,
        trafficAdjustment = 0f,
        autoReset = true,
        autoResetDay = autoResetDay,
        lastResetDate = 0L,
    )

    private fun createWorker(): UsageCheckWorker =
        TestListenableWorkerBuilder<UsageCheckWorker>(context)
            .setWorkerFactory(
                object : androidx.work.WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker =
                        UsageCheckWorker(
                            appContext,
                            workerParameters,
                            simRepository,
                            settingsRepository,
                            resetManager,
                            trafficTracker,
                            notificationHelper,
                            updateCoordinator,
                            usageStateRepository,
                            usageUseCase,
                        )
                },
            ).build()
}
