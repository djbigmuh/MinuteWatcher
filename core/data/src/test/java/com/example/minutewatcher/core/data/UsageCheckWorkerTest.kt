package com.example.minutewatcher.core.data

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.AutoResetUseCase
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimNotificationFlagsRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.UsageUseCase
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.DataSourceType
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.MeasurementUnit
import com.example.minutewatcher.core.common.util.FixedTimeProvider
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.SemanticEntity
import com.example.minutewatcher.core.model.SimSnapshot
import com.example.minutewatcher.core.model.SimUsage
import com.example.minutewatcher.core.resources.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UsageCheckWorkerTest {
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()
    private val simNotificationFlagsRepository: SimNotificationFlagsRepository = mock()
    private val appSettingsRepository: AppSettingsRepository = mock()
    private val autoResetUseCase: AutoResetUseCase = mock()
    private val trafficTracker: TrafficTracker = mock()
    private val notificationHelper: NotificationHelper = mock()
    private val dataChangeNotifier: DataChangeNotifier = mock()
    private val usageStateRepository: UsageStateRepository = mock()
    private val usageUseCase: UsageUseCase = mock()
    private val timeProvider = FixedTimeProvider(currentTime = 1000000L)

    private lateinit var worker: UsageCheckWorker

    @Before
    fun setup() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.READ_PHONE_STATE)

        whenever(usageStateRepository.observeSnapshot()).thenReturn(MutableStateFlow(createEmptySnapshot()))

        worker =
            TestListenableWorkerBuilder<UsageCheckWorker>(application)
                .setWorkerFactory(
                    object : androidx.work.WorkerFactory() {
                        override fun createWorker(
                            appContext: Context,
                            workerClassName: String,
                            workerParameters: androidx.work.WorkerParameters,
                        ): ListenableWorker =
                            UsageCheckWorker(
                                appContext,
                                workerParameters,
                                simIdentityRepository,
                                simUsageRepository,
                                simNotificationFlagsRepository,
                                appSettingsRepository,
                                autoResetUseCase,
                                trafficTracker,
                                notificationHelper,
                                dataChangeNotifier,
                                usageStateRepository,
                                usageUseCase,
                                timeProvider,
                                kotlinx.coroutines.test.UnconfinedTestDispatcher(),
                            )
                    },
                ).build()
    }

    @Test
    fun `doWork sends notification when minutes low`() =
        runTest {
            val identity = "sim_0"
            val snapshot =
                GlobalSnapshot(
                    version = SchemaVersion(1, 0, 0),
                    timestamp = timeProvider.now(),
                    simSnapshots =
                        listOf(
                            SimSnapshot(
                                slot = 0,
                                operator = "MTS",
                                isActive = true,
                                entities =
                                    mapOf(
                                        PackageType.MINUTES to
                                            SemanticEntity(
                                                id = "e1",
                                                simSlot = 0,
                                                operatorName = "MTS",
                                                packageType = PackageType.MINUTES,
                                                limit = 100,
                                                used = 90,
                                                remaining = 10,
                                                unit = MeasurementUnit.MINUTES,
                                                periodStart = 0,
                                                periodEnd = 0,
                                                lastUpdateTime = 0,
                                                sourceType = DataSourceType.SYSTEM,
                                            ),
                                    ),
                            ),
                        ),
                    capabilities = emptyList(),
                )

            whenever(usageStateRepository.refresh()).thenReturn(snapshot)
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(MutableStateFlow(AppSettings(notifyLowMinutes = true)))
            whenever(simIdentityRepository.getIdentity(0)).thenReturn(identity)
            whenever(simUsageRepository.getSimUsage(identity)).thenReturn(flowOf(createSimUsage(identity, minutesUsed = 90, minutesLimit = 100)))
            whenever(simNotificationFlagsRepository.getNotifiedLowMinutes(identity)).thenReturn(flowOf(false))
            whenever(simNotificationFlagsRepository.getNotifiedExhaustedMinutes(identity)).thenReturn(flowOf(false))

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(notificationHelper).sendUsageNotification(any(), any(), eq(0), eq(0))
            verify(simNotificationFlagsRepository).setNotifiedLowMinutes(eq(identity), eq(true))
        }

    @Test
    fun `doWork sends notification when traffic low`() =
        runTest {
            val identity = "sim_0"
            val snapshot =
                GlobalSnapshot(
                    version = SchemaVersion(1, 0, 0),
                    timestamp = timeProvider.now(),
                    simSnapshots =
                        listOf(
                            SimSnapshot(
                                slot = 0,
                                operator = "MTS",
                                isActive = true,
                                entities =
                                    mapOf(
                                        PackageType.DATA to
                                            SemanticEntity(
                                                id = "e2",
                                                simSlot = 0,
                                                operatorName = "MTS",
                                                packageType = PackageType.DATA,
                                                limit = com.example.minutewatcher.core.model.Constants.BYTES_PER_GB,
                                                used = 900 * 1024 * 1024,
                                                remaining = 124 * 1024 * 1024,
                                                unit = MeasurementUnit.BYTES,
                                                periodStart = 0,
                                                periodEnd = 0,
                                                lastUpdateTime = 0,
                                                sourceType = DataSourceType.SYSTEM,
                                            ),
                                    ),
                            ),
                        ),
                    capabilities = emptyList(),
                )

            whenever(usageStateRepository.refresh()).thenReturn(snapshot)
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(MutableStateFlow(AppSettings(notifyLowTraffic = true)))
            whenever(simIdentityRepository.getIdentity(0)).thenReturn(identity)
            whenever(simUsageRepository.getSimUsage(identity)).thenReturn(flowOf(createSimUsage(identity, trafficUsed = 900f, trafficLimit = 1024)))
            whenever(simNotificationFlagsRepository.getNotifiedLowTraffic(identity)).thenReturn(flowOf(false))
            whenever(simNotificationFlagsRepository.getNotifiedExhaustedTraffic(identity)).thenReturn(flowOf(false))

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(notificationHelper).sendUsageNotification(any(), any(), eq(0), eq(1))
            verify(simNotificationFlagsRepository).setNotifiedLowTraffic(eq(identity), eq(true))
        }

    @Test
    fun `doWork does not notify when usage is below progress threshold`() =
        runTest {
            // Setup time: Jan 15 (approx 48% through month if reset day is 1)
            timeProvider.setTime(createUtcMillis(2026, 1, 15))
            val identity = "sim_0"
            
            val snapshot = GlobalSnapshot(
                version = SchemaVersion(1, 0, 0),
                timestamp = timeProvider.now(),
                simSnapshots = listOf(
                    SimSnapshot(
                        slot = 0, operator = "MTS", isActive = true,
                        entities = mapOf(
                            PackageType.MINUTES to SemanticEntity(
                                id = "e1", simSlot = 0, operatorName = "MTS", packageType = PackageType.MINUTES,
                                limit = 100, used = 10, remaining = 90, // 10% used, < 48% progress
                                unit = MeasurementUnit.MINUTES, periodStart = 0, periodEnd = 0, lastUpdateTime = 0, sourceType = DataSourceType.SYSTEM
                            )
                        )
                    )
                ),
                capabilities = emptyList()
            )

            whenever(usageStateRepository.refresh()).thenReturn(snapshot)
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(MutableStateFlow(AppSettings(notifyLowMinutes = true)))
            whenever(simIdentityRepository.getIdentity(0)).thenReturn(identity)
            whenever(simUsageRepository.getSimUsage(identity)).thenReturn(flowOf(createSimUsage(identity, minutesUsed = 10, minutesLimit = 100, autoResetDay = 1)))
            whenever(simNotificationFlagsRepository.getNotifiedLowMinutes(identity)).thenReturn(flowOf(false))
            whenever(simNotificationFlagsRepository.getNotifiedExhaustedMinutes(identity)).thenReturn(flowOf(false))

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            // Verify NO notification sent
            verify(notificationHelper, never()).sendUsageNotification(any(), any(), any(), any())
        }

    @Test
    fun `traffic critical notification uses critical level`() =
        runTest {
            val identity = "sim_0"
            // Use 95% used, 5% remaining ( < 10% critical threshold)
            val limit = 1000L * 1024 * 1024
            val used = 950L * 1024 * 1024
            val remaining = 50L * 1024 * 1024

            val snapshot = GlobalSnapshot(
                version = SchemaVersion(1, 0, 0),
                timestamp = timeProvider.now(),
                simSnapshots = listOf(
                    SimSnapshot(
                        slot = 0, operator = "MTS", isActive = true,
                        entities = mapOf(
                            PackageType.DATA to SemanticEntity(
                                id = "e2", simSlot = 0, operatorName = "MTS", packageType = PackageType.DATA,
                                limit = limit, used = used, remaining = remaining,
                                unit = MeasurementUnit.BYTES, periodStart = 0, periodEnd = 0, lastUpdateTime = 0, sourceType = DataSourceType.SYSTEM
                            )
                        )
                    )
                ),
                capabilities = emptyList()
            )

            whenever(usageStateRepository.refresh()).thenReturn(snapshot)
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(MutableStateFlow(AppSettings(notifyLowTraffic = true)))
            whenever(simIdentityRepository.getIdentity(0)).thenReturn(identity)
            whenever(simUsageRepository.getSimUsage(identity)).thenReturn(flowOf(createSimUsage(identity, trafficUsed = 950f, trafficLimit = 1000)))
            whenever(simNotificationFlagsRepository.getNotifiedLowTraffic(identity)).thenReturn(flowOf(false))
            whenever(simNotificationFlagsRepository.getNotifiedExhaustedTraffic(identity)).thenReturn(flowOf(false))

            worker.doWork()

            val criticalLabel = ApplicationProvider.getApplicationContext<Context>().getString(R.string.usage_level_critical)
            verify(notificationHelper).sendUsageNotification(
                title = any(),
                message = argThat { contains(criticalLabel) },
                simSlot = eq(0),
                type = eq(1)
            )
        }

    @Suppress("SameParameterValue")
    private fun createUtcMillis(year: Int, month: Int, day: Int): Long {
        return java.time.LocalDateTime.of(year, month, day, 12, 0)
            .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
    }

    private fun createEmptySnapshot() = GlobalSnapshot(
        version = SchemaVersion(1, 0, 0),
        timestamp = 0,
        simSnapshots = emptyList(),
        capabilities = emptyList()
    )

    private fun createSimUsage(
        id: String,
        minutesUsed: Int = 0,
        minutesLimit: Int = 0,
        trafficUsed: Float = 0f,
        trafficLimit: Int = 0,
        autoResetDay: Int = 1,
    ) = SimUsage(
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
        rolloverEnabled = false
    )
}


