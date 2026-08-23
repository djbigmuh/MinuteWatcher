package com.example.minutewatcher.core.data

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.minutewatcher.core.common.util.FixedTimeProvider
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.AutoResetUseCase
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimNotificationFlagsRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.SimWidgetVisibilityRepository
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
import com.example.minutewatcher.core.resources.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class UsageCheckWorkerTest {
    private val simUsageRepository: SimUsageRepository = mock()
    private val simNotificationFlagsRepository: SimNotificationFlagsRepository = mock()
    private val appSettingsRepository: AppSettingsRepository = mock()
    private val autoResetUseCase: AutoResetUseCase = mock()
    private val trafficTracker: TrafficTracker = mock()
    private val notificationHelper: NotificationHelper = mock()
    private val dataChangeNotifier: DataChangeNotifier = mock()
    private val usageStateRepository: UsageStateRepository = mock()
    private val usageUseCase: UsageUseCase = mock()
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simWidgetVisibilityRepository: SimWidgetVisibilityRepository = mock()
    private val timeProvider = FixedTimeProvider(currentTime = 1000000L)

    private val testDispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher()

    private lateinit var worker: UsageCheckWorker

    @Before
    fun setup() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.READ_PHONE_STATE)

        whenever(usageStateRepository.observeSnapshot()).thenReturn(MutableStateFlow(createEmptySnapshot()))

        // Setup default stubs for all suspend methods to prevent hangs on unstubbed calls
        runTest(testDispatcher) {
            whenever(usageUseCase.refreshOperatorInfo()).thenReturn(Unit)
            whenever(trafficTracker.updateTrafficUsage()).thenReturn(Unit)
            whenever(autoResetUseCase.performAutoReset(any())).thenReturn(Unit)
            whenever(dataChangeNotifier.notifyDataChanged(any(), any())).thenReturn(Unit)
            whenever(simWidgetVisibilityRepository.clearOrphanedSettings(any())).thenReturn(Unit)
        }

        whenever(appSettingsRepository.getAppSettings()).thenReturn(flowOf(AppSettings()))
        whenever(simUsageRepository.getSimUsage(any())).thenReturn(flowOf(createSimUsage("default")))
        whenever(simNotificationFlagsRepository.getNotifiedLowMinutes(any())).thenReturn(flowOf(false))
        whenever(simNotificationFlagsRepository.getNotifiedExhaustedMinutes(any())).thenReturn(flowOf(false))
        whenever(simNotificationFlagsRepository.getNotifiedLowTraffic(any())).thenReturn(flowOf(false))
        whenever(simNotificationFlagsRepository.getNotifiedExhaustedTraffic(any())).thenReturn(flowOf(false))

        worker =
            TestListenableWorkerBuilder<UsageCheckWorker>(application)
                .setWorkerFactory(
                    object : androidx.work.WorkerFactory() {
                        override fun createWorker(
                            appContext: Context,
                            workerClassName: String,
                            workerParameters: androidx.work.WorkerParameters,
                        ): ListenableWorker {
                            val deps =
                                UsageWorkerDependencies(
                                    simUsageRepository = simUsageRepository,
                                    simIdentityRepository = simIdentityRepository,
                                    simNotificationFlagsRepository = simNotificationFlagsRepository,
                                    appSettingsRepository = appSettingsRepository,
                                    autoResetUseCase = autoResetUseCase,
                                    trafficTracker = trafficTracker,
                                    notificationHelper = notificationHelper,
                                    dataChangeNotifier = dataChangeNotifier,
                                    usageStateRepository = usageStateRepository,
                                    usageUseCase = usageUseCase,
                                    simWidgetVisibilityRepository = simWidgetVisibilityRepository,
                                    timeProvider = timeProvider,
                                )
                            return UsageCheckWorker(
                                appContext,
                                workerParameters,
                                deps,
                                testDispatcher,
                            )
                        }
                    },
                ).build()
    }

    @Test
    fun `doWork sends notification when minutes low`() =
        runTest(testDispatcher) {
            val identity = "sim_0"
            val snapshot =
                GlobalSnapshot(
                    version = SchemaVersion(1, 0, 0),
                    timestamp = timeProvider.now(),
                    simSnapshots =
                        listOf(
                            SimSnapshot(
                                slot = 0,
                                identity = "sim_0",
                                operatorName = "MTS",
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
                                autoResetDay = 1,
                            ),
                        ),
                    capabilities = emptyList(),
                )

            whenever(usageStateRepository.refresh()).thenReturn(
                com.example.minutewatcher.core.domain.RefreshResult
                    .Success(snapshot),
            )
            whenever(appSettingsRepository.getAppSettings()).thenReturn(flowOf(AppSettings(notifyLowMinutes = true)))
            whenever(simUsageRepository.getSimUsage(identity))
                .thenReturn(flowOf(createSimUsage(identity, minutesUsed = 90, minutesLimit = 100)))
            whenever(simNotificationFlagsRepository.getNotifiedLowMinutes(identity)).thenReturn(flowOf(false))
            whenever(simNotificationFlagsRepository.getNotifiedExhaustedMinutes(identity)).thenReturn(flowOf(false))

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(notificationHelper).sendUsageNotification(any(), any(), eq(0), eq(0))
            verify(simNotificationFlagsRepository).setNotifiedLowMinutes(eq(identity), eq(true))
        }

    @Test
    fun `doWork retries on IOException`() =
        runTest(testDispatcher) {
            whenever(usageUseCase.refreshOperatorInfo()).thenAnswer { throw java.io.IOException("Disk full") }
            val result = worker.doWork()
            assertEquals(ListenableWorker.Result.retry(), result)
        }

    @Test
    fun `doWork fails on unexpected Exception`() =
        runTest(testDispatcher) {
            whenever(usageUseCase.refreshOperatorInfo()).thenAnswer { throw RuntimeException("Fatal") }
            val result = worker.doWork()
            assertEquals(ListenableWorker.Result.failure(), result)
        }

    @Test
    fun `doWork sends notification when traffic low`() =
        runTest(testDispatcher) {
            val identity = "sim_0"
            val snapshot =
                GlobalSnapshot(
                    version = SchemaVersion(1, 0, 0),
                    timestamp = timeProvider.now(),
                    simSnapshots =
                        listOf(
                            SimSnapshot(
                                slot = 0,
                                identity = "sim_0",
                                operatorName = "MTS",
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
                                autoResetDay = 1,
                            ),
                        ),
                    capabilities = emptyList(),
                )

            whenever(usageStateRepository.refresh()).thenReturn(
                com.example.minutewatcher.core.domain.RefreshResult
                    .Success(snapshot),
            )
            whenever(appSettingsRepository.getAppSettings()).thenReturn(flowOf(AppSettings(notifyLowTraffic = true)))
            whenever(simUsageRepository.getSimUsage(identity))
                .thenReturn(flowOf(createSimUsage(identity, trafficUsedMb = 900f, trafficLimitMb = 1024)))
            whenever(simNotificationFlagsRepository.getNotifiedLowTraffic(identity)).thenReturn(flowOf(false))
            whenever(simNotificationFlagsRepository.getNotifiedExhaustedTraffic(identity)).thenReturn(flowOf(false))

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(notificationHelper).sendUsageNotification(any(), any(), eq(0), eq(1))
            verify(simNotificationFlagsRepository).setNotifiedLowTraffic(eq(identity), eq(true))
        }

    @Test
    fun `doWork does not notify when usage is below progress threshold`() =
        runTest(testDispatcher) {
            // Setup time: Jan 15 (approx 48% through month if reset day is 1)
            timeProvider.setTime(createUtcMillis(2026, 1, 15))
            val identity = "sim_0"

            val snapshot =
                GlobalSnapshot(
                    version = SchemaVersion(1, 0, 0),
                    timestamp = timeProvider.now(),
                    simSnapshots =
                        listOf(
                            SimSnapshot(
                                slot = 0,
                                identity = "sim_0",
                                operatorName = "MTS",
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
                                                used = 10,
                                                remaining = 90, // 10% used, < 48% progress
                                                unit = MeasurementUnit.MINUTES,
                                                periodStart = 0,
                                                periodEnd = 0,
                                                lastUpdateTime = 0,
                                                sourceType = DataSourceType.SYSTEM,
                                            ),
                                    ),
                                autoResetDay = 1,
                            ),
                        ),
                    capabilities = emptyList(),
                )

            whenever(usageStateRepository.refresh()).thenReturn(
                com.example.minutewatcher.core.domain.RefreshResult
                    .Success(snapshot),
            )
            whenever(appSettingsRepository.getAppSettings()).thenReturn(flowOf(AppSettings(notifyLowMinutes = true)))
            whenever(simUsageRepository.getSimUsage(identity))
                .thenReturn(flowOf(createSimUsage(identity, minutesUsed = 10, minutesLimit = 100, autoResetDay = 1)))
            whenever(simNotificationFlagsRepository.getNotifiedLowMinutes(identity)).thenReturn(flowOf(false))
            whenever(simNotificationFlagsRepository.getNotifiedExhaustedMinutes(identity)).thenReturn(flowOf(false))

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            // Verify NO notification sent
            verify(notificationHelper, never()).sendUsageNotification(any(), any(), any(), any())
        }

    @Test
    fun `doWork sends notification when usage is high near the end of month`() =
        runTest(testDispatcher) {
            // Setup time: Oct 16
            timeProvider.setTime(createUtcMillis(2027, 10, 16))
            val identity = "sim_0"

            val snapshot =
                GlobalSnapshot(
                    version = SchemaVersion(1, 0, 0),
                    timestamp = timeProvider.now(),
                    simSnapshots =
                        listOf(
                            SimSnapshot(
                                slot = 0,
                                identity = "sim_0",
                                operatorName = "MTS",
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
                                                used = 98,
                                                remaining = 2,
                                                unit = MeasurementUnit.MINUTES,
                                                periodStart = 0,
                                                periodEnd = 0,
                                                lastUpdateTime = 0,
                                                sourceType = DataSourceType.SYSTEM,
                                            ),
                                    ),
                                autoResetDay = 1,
                            ),
                        ),
                    capabilities = emptyList(),
                )

            whenever(usageStateRepository.refresh()).thenReturn(
                com.example.minutewatcher.core.domain.RefreshResult
                    .Success(snapshot),
            )
            whenever(appSettingsRepository.getAppSettings()).thenReturn(flowOf(AppSettings(notifyLowMinutes = true)))
            whenever(simUsageRepository.getSimUsage(identity))
                .thenReturn(flowOf(createSimUsage(identity, minutesUsed = 98, minutesLimit = 100)))

            worker.doWork()

            verify(notificationHelper).sendUsageNotification(any(), any(), eq(0), eq(0))
        }

    @Test
    @Suppress("LongMethod", "detekt.LongMethod")
    fun `traffic critical notification uses critical level`() =
        runTest(testDispatcher) {
            val identity = "sim_0"
            // Use 95% used, 5% remaining ( < 10% critical threshold)
            val limit = 1000L * 1024 * 1024
            val used = 950L * 1024 * 1024
            val remaining = 50L * 1024 * 1024

            val snapshot =
                GlobalSnapshot(
                    version = SchemaVersion(1, 0, 0),
                    timestamp = timeProvider.now(),
                    simSnapshots =
                        listOf(
                            SimSnapshot(
                                slot = 0,
                                identity = "sim_0",
                                operatorName = "MTS",
                                isActive = true,
                                entities =
                                    mapOf(
                                        PackageType.DATA to
                                            SemanticEntity(
                                                id = "e2",
                                                simSlot = 0,
                                                operatorName = "MTS",
                                                packageType = PackageType.DATA,
                                                limit = limit,
                                                used = used,
                                                remaining = remaining,
                                                unit = MeasurementUnit.BYTES,
                                                periodStart = 0,
                                                periodEnd = 0,
                                                lastUpdateTime = 0,
                                                sourceType = DataSourceType.SYSTEM,
                                            ),
                                    ),
                                autoResetDay = 1,
                            ),
                        ),
                    capabilities = emptyList(),
                )

            whenever(usageStateRepository.refresh()).thenReturn(
                com.example.minutewatcher.core.domain.RefreshResult
                    .Success(snapshot),
            )
            whenever(appSettingsRepository.getAppSettings()).thenReturn(flowOf(AppSettings(notifyLowTraffic = true)))
            whenever(simUsageRepository.getSimUsage(identity))
                .thenReturn(flowOf(createSimUsage(identity, trafficUsedMb = 950f, trafficLimitMb = 1000)))
            whenever(simNotificationFlagsRepository.getNotifiedLowTraffic(identity)).thenReturn(flowOf(false))
            whenever(simNotificationFlagsRepository.getNotifiedExhaustedTraffic(identity)).thenReturn(flowOf(false))

            worker.doWork()

            val criticalLabel =
                ApplicationProvider.getApplicationContext<Context>().getString(
                    R.string.usage_level_critical,
                )
            verify(notificationHelper).sendUsageNotification(
                title = any(),
                message = argThat { contains(criticalLabel) },
                simSlot = eq(0),
                type = eq(1),
            )
        }

    @Test
    fun `doWork continues even when READ_PHONE_STATE is missing`() =
        runTest(testDispatcher) {
            // 1. Revoke permission in shadow
            val application = ApplicationProvider.getApplicationContext<Application>()
            Shadows.shadowOf(application).denyPermissions(Manifest.permission.READ_PHONE_STATE)

            // 2. Setup mocks to return empty/idle state (which is what happens without permission)
            whenever(appSettingsRepository.getAppSettings()).thenReturn(flowOf(AppSettings()))
            whenever(usageStateRepository.refresh())
                .thenReturn(
                    com.example.minutewatcher.core.domain.RefreshResult
                        .Success(createEmptySnapshot()),
                )

            // 3. Act
            val result = worker.doWork()

            // 4. Assert
            assertEquals(ListenableWorker.Result.success(), result)
            // Verify that it still reached the logic and called refresh
            verify(usageStateRepository, atLeastOnce()).refresh()
            verify(usageUseCase, atLeastOnce()).refreshOperatorInfo()
        }

    @Test
    fun `doWork uses default settings when AppSettings read times out`() =
        runTest(testDispatcher) {
            val identity = "sim_0"
            val snapshot =
                GlobalSnapshot(
                    version = SchemaVersion(1, 0, 0),
                    timestamp = timeProvider.now(),
                    simSnapshots =
                        listOf(
                            SimSnapshot(
                                slot = 0,
                                identity = "sim_0",
                                operatorName = "MTS",
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
                                autoResetDay = 1,
                            ),
                        ),
                    capabilities = emptyList(),
                )

            whenever(usageStateRepository.refresh()).thenReturn(
                com.example.minutewatcher.core.domain.RefreshResult
                    .Success(snapshot),
            )

            // Mock timeout: a flow that never emits
            whenever(appSettingsRepository.getAppSettings()).thenReturn(MutableSharedFlow())

            whenever(simUsageRepository.getSimUsage(identity))
                .thenReturn(flowOf(createSimUsage(identity, minutesUsed = 90, minutesLimit = 100)))
            whenever(simNotificationFlagsRepository.getNotifiedLowMinutes(identity)).thenReturn(flowOf(false))
            whenever(simNotificationFlagsRepository.getNotifiedExhaustedMinutes(identity)).thenReturn(flowOf(false))

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            // By default AppSettings has notifyLowMinutes = true, so notification SHOULD be sent
            verify(notificationHelper).sendUsageNotification(any(), any(), eq(0), eq(0))
        }

    @Test
    fun `doWork skips notifications on refresh timeout but updates widget`() =
        runTest(testDispatcher) {
            val identity = "sim_0"
            val staleTimestamp = 500000L
            val snapshot =
                GlobalSnapshot(
                    version = SchemaVersion(1, 0, 0),
                    timestamp = staleTimestamp,
                    simSnapshots =
                        listOf(
                            SimSnapshot(
                                slot = 0,
                                identity = identity,
                                operatorName = "MTS",
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
                                autoResetDay = 1,
                            ),
                        ),
                    capabilities = emptyList(),
                )

            // Mock timeout with a valid (stale) snapshot
            whenever(usageStateRepository.refresh()).thenReturn(
                com.example.minutewatcher.core.domain.RefreshResult
                    .Timeout(snapshot),
            )

            whenever(appSettingsRepository.getAppSettings()).thenReturn(flowOf(AppSettings(notifyLowMinutes = true)))
            whenever(simUsageRepository.getSimUsage(identity))
                .thenReturn(flowOf(createSimUsage(identity, minutesUsed = 90, minutesLimit = 100)))

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)

            // Verify widget update was STILL called (using the stale data is okay for UI)
            verify(dataChangeNotifier).notifyDataChanged(updateWidget = eq(true))

            // Verify it SKIPPED notifications
            verify(notificationHelper, never()).sendUsageNotification(any(), any(), any(), any())
        }

    @Test
    fun `doWork retries when refresh times out and no data loaded`() =
        runTest(testDispatcher) {
            // Mock timeout with empty snapshot (timestamp = 0)
            whenever(usageStateRepository.refresh()).thenReturn(
                com.example.minutewatcher.core.domain.RefreshResult
                    .Timeout(createEmptySnapshot()),
            )

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
            verify(notificationHelper, never()).sendUsageNotification(any(), any(), any(), any())
        }

    private fun createUtcMillis(
        year: Int,
        month: Int,
        day: Int,
    ): Long =
        java.time.LocalDateTime
            .of(year, month, day, 12, 0)
            .toInstant(java.time.ZoneOffset.UTC)
            .toEpochMilli()

    private fun createEmptySnapshot() =
        GlobalSnapshot(
            version = SchemaVersion(1, 0, 0),
            timestamp = 0,
            simSnapshots = emptyList(),
            capabilities = emptyList(),
        )

    private fun createSimUsage(
        id: String,
        minutesUsed: Int = 0,
        minutesLimit: Int = 0,
        trafficUsedMb: Float = 0f,
        trafficLimitMb: Int = 0,
        autoResetDay: Int = 1,
    ) = SimUsage(
        identity = id,
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
        autoResetDay = autoResetDay,
        lastResetDate = 0L,
        rolloverEnabled = false,
    )
}
