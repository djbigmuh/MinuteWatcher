package com.example.minutewatcher.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.RefreshResult
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.SemanticEntity
import com.example.minutewatcher.core.model.SimSnapshot
import com.example.minutewatcher.core.model.SimUsage
import com.example.minutewatcher.core.resources.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class UsageCheckWorkerTest {
    private val usageStateRepository: UsageStateRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()
    private val appSettingsRepository: AppSettingsRepository = mock()
    private val notificationHelper: NotificationHelper = mock()
    private val permissionManager: PermissionManager = mock()
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var worker: UsageCheckWorker
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val workerParams: WorkerParameters = mock()
        worker =
            UsageCheckWorker(
                context,
                workerParams,
                usageStateRepository,
                simUsageRepository,
                appSettingsRepository,
                notificationHelper,
                permissionManager,
            )
    }

    private suspend fun setupDefaultMocks() {
        whenever(permissionManager.hasNotificationPermission()).thenReturn(true)
        whenever(appSettingsRepository.getAppSettings())
            .thenReturn(flowOf(AppSettings(notifyLowMinutes = true, notifyLowTraffic = true, isLoaded = true)))
    }

    private fun createUsageSnapshot(
        minutesUsed: Int = 0,
        minutesLimit: Int = 0,
        identity: String = "sim_0",
    ): GlobalSnapshot {
        val entity =
            SemanticEntity(
                slot = 0,
                operatorName = "MTS",
                type = PackageType.MINUTES,
                used = minutesUsed.toLong(),
                limit = minutesLimit.toLong(),
                remaining = (minutesLimit - minutesUsed).toLong(),
                resetDay = 1,
                lastUpdate = 0L,
            )
        val simSnapshot =
            SimSnapshot(
                slot = 0,
                identity = identity,
                operatorName = "MTS",
                entities = mapOf(PackageType.MINUTES to entity),
                autoResetDay = 1,
            )
        return GlobalSnapshot(
            version = SchemaVersion(1, 0, 0),
            timestamp = 1000L,
            simSnapshots = listOf(simSnapshot),
            capabilities = emptyList(),
        )
    }

    private fun createTrafficSnapshot(
        identity: String,
        used: Long,
        limit: Long,
        remaining: Long,
    ): GlobalSnapshot {
        val entity =
            SemanticEntity(
                slot = 0,
                operatorName = "MTS",
                type = PackageType.DATA,
                used = used,
                limit = limit,
                remaining = remaining,
                resetDay = 1,
                lastUpdate = 0L,
            )
        val simSnapshot =
            SimSnapshot(
                slot = 0,
                identity = identity,
                operatorName = "MTS",
                entities = mapOf(PackageType.DATA to entity),
                autoResetDay = 1,
            )
        return GlobalSnapshot(
            version = SchemaVersion(1, 0, 0),
            timestamp = 1000L,
            simSnapshots = listOf(simSnapshot),
            capabilities = emptyList(),
        )
    }

    private fun createSimUsage(
        identity: String,
        minutesUsed: Int = 0,
        minutesLimit: Int = 0,
        trafficUsedMb: Float = 0f,
        trafficLimitMb: Int = 0,
    ) = SimUsage(
        identity = identity,
        slotIndex = 0,
        subscriptionId = 1,
        operator = "MTS",
        minutesUsed = minutesUsed,
        minutesLimit = minutesLimit,
        trafficUsedMb = trafficUsedMb,
        trafficLimitMb = trafficLimitMb,
        manualAdjustment = 0,
        trafficAdjustmentGb = 0f,
        autoReset = true,
        autoResetDay = 1,
        lastResetDate = 0L,
        rolloverEnabled = false,
    )

    @Test
    fun `doWork returns success when no snapshots`() =
        runTest(testDispatcher) {
            whenever(usageStateRepository.refresh()).thenReturn(RefreshResult.Timeout(null))
            val result = worker.doWork()
            assertEquals(ListenableWorker.Result.success(), result)
        }

    @Test
    fun `doWork sends notification when minutes low`() =
        runTest(testDispatcher) {
            setupDefaultMocks()
            val identity = "sim_0"
            val snapshot = createUsageSnapshot(minutesUsed = 81, minutesLimit = 100)

            whenever(usageStateRepository.refresh()).thenReturn(RefreshResult.Success(snapshot))
            whenever(simUsageRepository.getSimUsage(identity)).thenReturn(flowOf(createSimUsage(identity)))

            worker.doWork()

            verify(notificationHelper).sendUsageNotification(any(), any(), eq(0), eq(0))
        }

    @Test
    fun `traffic critical notification uses critical level`() =
        runTest(testDispatcher) {
            val identity = "sim_0"
            setupTrafficCriticalScenario(identity)

            worker.doWork()

            verifyTrafficCriticalNotification()
        }

    private suspend fun setupTrafficCriticalScenario(identity: String) {
        setupDefaultMocks()
        // Use 95% used, 5% remaining ( < 10% critical threshold)
        val limit = 1000L * 1024 * 1024
        val used = 950L * 1024 * 1024
        val remaining = 50L * 1024 * 1024

        val snapshot = createTrafficSnapshot(identity, used, limit, remaining)

        whenever(usageStateRepository.refresh()).thenReturn(RefreshResult.Success(snapshot))
        whenever(simUsageRepository.getSimUsage(identity))
            .thenReturn(flowOf(createSimUsage(identity, trafficUsedMb = 950f, trafficLimitMb = 1000)))
    }

    private fun verifyTrafficCriticalNotification() {
        val criticalLabel = context.getString(R.string.usage_level_critical)
        verify(notificationHelper).sendUsageNotification(
            title = any(),
            message = argThat { contains(criticalLabel) },
            simSlot = eq(0),
            type = eq(NOTIFICATION_TYPE_TRAFFIC),
        )
    }

    @Test
    fun `doWork skips notifications when settings load fails`() =
        runTest(testDispatcher) {
            setupDefaultMocks()
            val identity = "sim_0"
            val snapshot = createUsageSnapshot(minutesUsed = 81, minutesLimit = 100)

            whenever(usageStateRepository.refresh()).thenReturn(RefreshResult.Success(snapshot))
            // Mock timeout: a flow that never emits
            whenever(appSettingsRepository.getAppSettings()).thenReturn(MutableSharedFlow())
            whenever(simUsageRepository.getSimUsage(identity)).thenReturn(flowOf(createSimUsage(identity)))

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(notificationHelper, never()).sendUsageNotification(any(), any(), any(), any())
        }

    private companion object {
        private const val NOTIFICATION_TYPE_TRAFFIC = 1
    }
}
