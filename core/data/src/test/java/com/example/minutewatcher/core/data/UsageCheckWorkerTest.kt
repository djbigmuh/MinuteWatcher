package com.example.minutewatcher.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import com.example.minutewatcher.core.common.util.FixedTimeProvider
import com.example.minutewatcher.core.domain.AutoResetUseCase
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.RefreshResult
import com.example.minutewatcher.core.domain.SimNotificationFlagsRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
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
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.Calendar
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
@OptIn(ExperimentalCoroutinesApi::class)
class UsageCheckWorkerTest {
    private val usageStateRepository: UsageStateRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()
    private val appSettingsRepository: com.example.minutewatcher.core.domain.AppSettingsRepository = mock()
    private val simNotificationFlagsRepository: SimNotificationFlagsRepository = mock()
    private val notificationHelper: NotificationHelper = mock()
    private val dataChangeNotifier: DataChangeNotifier = mock()
    private val usageUseCase: UsageUseCase = mock()
    private val autoResetUseCase: AutoResetUseCase = mock()
    private val trafficTracker: TrafficTracker = mock()
    private val timeProvider = FixedTimeProvider()
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var worker: UsageCheckWorker

    @Before
    fun setup() {
        val deps =
            UsageWorkerDependencies(
                simUsageRepository = simUsageRepository,
                simNotificationFlagsRepository = simNotificationFlagsRepository,
                appSettingsRepository = appSettingsRepository,
                autoResetUseCase = autoResetUseCase,
                trafficTracker = trafficTracker,
                notificationHelper = notificationHelper,
                dataChangeNotifier = dataChangeNotifier,
                usageStateRepository = usageStateRepository,
                usageUseCase = usageUseCase,
                timeProvider = timeProvider,
            )
        worker =
            UsageCheckWorker(
                context = ApplicationProvider.getApplicationContext(),
                params = mock(),
                deps = deps,
                ioDispatcher = testDispatcher,
            )
    }

    private suspend fun setupDefaultMocks() {
        whenever(appSettingsRepository.getAppSettings()).thenReturn(flowOf(AppSettings(isLoaded = true)))
        whenever(usageUseCase.refreshOperatorInfo()).thenReturn(Unit)
        whenever(autoResetUseCase.performAutoReset(any())).thenReturn(true)
        whenever(usageStateRepository.refresh()).thenReturn(RefreshResult.Success(createEmptySnapshot()))

        whenever(simNotificationFlagsRepository.getNotifiedExhaustedMinutes(any())).thenReturn(flowOf(false))
        whenever(simNotificationFlagsRepository.getNotifiedExhaustedTraffic(any())).thenReturn(flowOf(false))
        whenever(simNotificationFlagsRepository.getNotifiedLowMinutes(any())).thenReturn(flowOf(false))
        whenever(simNotificationFlagsRepository.getNotifiedLowTraffic(any())).thenReturn(flowOf(false))
    }

    private fun createEmptySnapshot(): GlobalSnapshot =
        GlobalSnapshot(
            version = SchemaVersion(1, 0, 0),
            timestamp = timeProvider.now(),
            simSnapshots = emptyList(),
            capabilities = emptyList(),
        )

    private fun createUsageSnapshot(
        minutesUsed: Long,
        minutesLimit: Long,
    ): GlobalSnapshot =
        GlobalSnapshot(
            version = SchemaVersion(1, 0, 0),
            timestamp = timeProvider.now(),
            simSnapshots =
                listOf(
                    SimSnapshot(
                        slot = 0,
                        identity = "sim_0",
                        operatorName = "MTS",
                        entities =
                            mapOf(
                                PackageType.MINUTES to
                                    SemanticEntity(
                                        id = "e1",
                                        simSlot = 0,
                                        operatorName = "MTS",
                                        packageType = PackageType.MINUTES,
                                        limit = minutesLimit,
                                        used = minutesUsed,
                                        remaining = minutesLimit - minutesUsed,
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

    private fun createTrafficSnapshot(
        identity: String,
        used: Long,
        limit: Long,
        remaining: Long,
    ): GlobalSnapshot =
        GlobalSnapshot(
            version = SchemaVersion(1, 0, 0),
            timestamp = timeProvider.now(),
            simSnapshots =
                listOf(
                    SimSnapshot(
                        slot = 0,
                        identity = identity,
                        operatorName = "MTS",
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

    private fun createSimUsage(
        identity: String,
        minutesUsed: Int = 0,
        minutesLimit: Int = 0,
        trafficUsedMb: Float = 0f,
        trafficLimitMb: Int = 0,
    ): SimUsage =
        SimUsage(
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
            rolloverEnabled = false
        )

    private fun createUtcMillis(
        year: Int,
        month: Int,
        day: Int,
    ): Long {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.set(year, month - 1, day, 0, 0, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    @Test
    fun `doWork returns retry when refresh fails with IO`() =
        runTest(testDispatcher) {
            setupDefaultMocks()
            whenever(usageStateRepository.refresh()).thenThrow(IOException("error"))

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
            verify(notificationHelper, never()).sendUsageNotification(any(), any(), any(), any())
        }

    @Test
    fun `doWork sends LOW notification when minutes below 20 percent`() =
        runTest(testDispatcher) {
            setupDefaultMocks()
            val identity = "sim_0"
            val snapshot = createUsageSnapshot(minutesUsed = 81, minutesLimit = 100)

            whenever(usageStateRepository.refresh()).thenReturn(RefreshResult.Success(snapshot))
            whenever(appSettingsRepository.getAppSettings())
                .thenReturn(flowOf(AppSettings(notifyLowMinutes = true, isLoaded = true)))
            whenever(simUsageRepository.getSimUsage(identity))
                .thenReturn(flowOf(createSimUsage(identity, minutesUsed = 81, minutesLimit = 100)))

            worker.doWork()

            verify(notificationHelper).sendUsageNotification(any(), any(), eq(0), eq(0))
            verify(simNotificationFlagsRepository).setNotifiedLowMinutes(eq(identity), eq(true))
        }

    @Test
    fun `doWork does not send notification when already notified`() =
        runTest(testDispatcher) {
            setupDefaultMocks()
            val identity = "sim_0"
            val snapshot = createUsageSnapshot(minutesUsed = 81, minutesLimit = 100)

            whenever(usageStateRepository.refresh()).thenReturn(RefreshResult.Success(snapshot))
            whenever(appSettingsRepository.getAppSettings())
                .thenReturn(flowOf(AppSettings(notifyLowMinutes = true, isLoaded = true)))
            whenever(simUsageRepository.getSimUsage(identity))
                .thenReturn(flowOf(createSimUsage(identity, minutesUsed = 81, minutesLimit = 100)))
            whenever(simNotificationFlagsRepository.getNotifiedLowMinutes(identity)).thenReturn(flowOf(true))

            worker.doWork()

            verify(notificationHelper, never()).sendUsageNotification(any(), any(), any(), any())
        }

    @Test
    fun `doWork sends CRITICAL notification when minutes below 10 percent`() =
        runTest(testDispatcher) {
            setupDefaultMocks()
            val identity = "sim_0"
            val snapshot = createUsageSnapshot(minutesUsed = 91, minutesLimit = 100)

            whenever(usageStateRepository.refresh()).thenReturn(RefreshResult.Success(snapshot))
            whenever(appSettingsRepository.getAppSettings())
                .thenReturn(flowOf(AppSettings(notifyLowMinutes = true, isLoaded = true)))
            whenever(simUsageRepository.getSimUsage(identity))
                .thenReturn(flowOf(createSimUsage(identity, minutesUsed = 91, minutesLimit = 100)))

            worker.doWork()

            val criticalLabel =
                ApplicationProvider.getApplicationContext<Context>().getString(R.string.usage_level_critical)
            verify(notificationHelper).sendUsageNotification(
                title = any(),
                message = argThat { contains(criticalLabel) },
                simSlot = any(),
                type = any(),
            )
        }

    @Test
    fun `doWork resets notifiedLow flag when usage increases above threshold`() =
        runTest(testDispatcher) {
            setupDefaultMocks()
            val identity = "sim_0"
            val snapshot = createUsageSnapshot(minutesUsed = 75, minutesLimit = 100)

            whenever(usageStateRepository.refresh()).thenReturn(RefreshResult.Success(snapshot))
            whenever(appSettingsRepository.getAppSettings())
                .thenReturn(flowOf(AppSettings(notifyLowMinutes = true, isLoaded = true)))
            whenever(simUsageRepository.getSimUsage(identity))
                .thenReturn(flowOf(createSimUsage(identity, minutesUsed = 75, minutesLimit = 100)))
            // State: ALREADY NOTIFIED
            whenever(simNotificationFlagsRepository.getNotifiedLowMinutes(identity)).thenReturn(flowOf(true))

            worker.doWork()

            verify(simNotificationFlagsRepository).setNotifiedLowMinutes(eq(identity), eq(false))
            verify(notificationHelper, never()).sendUsageNotification(any(), any(), any(), any())
        }

    @Test
    fun `doWork sends notification when usage is high near the end of month`() =
        runTest(testDispatcher) {
            setupDefaultMocks()
            // Setup time: Oct 16
            timeProvider.setTime(createUtcMillis(2027, 10, 16))
            val identity = "sim_0"
            val snapshot = createUsageSnapshot(minutesUsed = 98, minutesLimit = 100)

            whenever(usageStateRepository.refresh()).thenReturn(RefreshResult.Success(snapshot))
            whenever(appSettingsRepository.getAppSettings())
                .thenReturn(flowOf(AppSettings(notifyLowMinutes = true, isLoaded = true)))
            whenever(simUsageRepository.getSimUsage(identity))
                .thenReturn(flowOf(createSimUsage(identity, minutesUsed = 98, minutesLimit = 100)))

            worker.doWork()

            verify(notificationHelper).sendUsageNotification(any(), any(), eq(0), eq(0))
        }

    @Test
    fun `traffic critical notification uses critical level`() =
        runTest(testDispatcher) {
            setupDefaultMocks()
            val identity = "sim_0"
            // Use 95% used, 5% remaining ( < 10% critical threshold)
            val limit = 1000L * 1024 * 1024
            val used = 950L * 1024 * 1024
            val remaining = 50L * 1024 * 1024

            val snapshot = createTrafficSnapshot(identity, used, limit, remaining)

            whenever(usageStateRepository.refresh()).thenReturn(RefreshResult.Success(snapshot))
            whenever(appSettingsRepository.getAppSettings())
                .thenReturn(flowOf(AppSettings(notifyLowTraffic = true, isLoaded = true)))
            whenever(simUsageRepository.getSimUsage(identity))
                .thenReturn(flowOf(createSimUsage(identity, trafficUsedMb = 950f, trafficLimitMb = 1000)))

            worker.doWork()

            val criticalLabel = ApplicationProvider.getApplicationContext<Context>()
                .getString(R.string.usage_level_critical)
            verify(notificationHelper).sendUsageNotification(
                title = any(),
                message = argThat { contains(criticalLabel) },
                simSlot = eq(0),
                type = eq(1),
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
