package host.serv.djbigmuh.minutewatcher.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import host.serv.djbigmuh.minutewatcher.core.common.util.FormatUtils
import host.serv.djbigmuh.minutewatcher.core.common.util.PermissionManager
import host.serv.djbigmuh.minutewatcher.core.common.util.TimeProvider
import host.serv.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import host.serv.djbigmuh.minutewatcher.core.domain.AutoResetUseCase
import host.serv.djbigmuh.minutewatcher.core.domain.DataChangeNotifier
import host.serv.djbigmuh.minutewatcher.core.domain.RefreshResult
import host.serv.djbigmuh.minutewatcher.core.domain.SimNotificationFlagsRepository
import host.serv.djbigmuh.minutewatcher.core.domain.UsageStateRepository
import host.serv.djbigmuh.minutewatcher.core.domain.UsageUseCase
import host.serv.djbigmuh.minutewatcher.core.model.AppSettings
import host.serv.djbigmuh.minutewatcher.core.model.DataSourceType
import host.serv.djbigmuh.minutewatcher.core.model.GlobalSnapshot
import host.serv.djbigmuh.minutewatcher.core.model.MeasurementUnit
import host.serv.djbigmuh.minutewatcher.core.model.PackageType
import host.serv.djbigmuh.minutewatcher.core.model.SchemaVersion
import host.serv.djbigmuh.minutewatcher.core.model.SemanticEntity
import host.serv.djbigmuh.minutewatcher.core.model.SimSnapshot
import host.serv.djbigmuh.minutewatcher.core.model.TrackingStatus
import host.serv.djbigmuh.minutewatcher.core.model.util.DateUtils
import host.serv.djbigmuh.minutewatcher.core.resources.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class UsageCheckWorkerTest {
    private val usageStateRepository: UsageStateRepository = mock()
    private val simNotificationFlagsRepository: SimNotificationFlagsRepository = mock()
    private val appSettingsRepository: AppSettingsRepository = mock()
    private val autoResetUseCase: AutoResetUseCase = mock()
    private val trafficTracker: TrafficTracker = mock()
    private val notificationHelper: NotificationHelper = mock()
    private val dataChangeNotifier: DataChangeNotifier = mock()
    private val usageUseCase: UsageUseCase = mock()
    private val timeProvider: TimeProvider = mock()
    private val permissionManager: PermissionManager = mock()
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var worker: UsageCheckWorker
    private lateinit var context: Context

    @Before
    fun setup() =
        runTest(testDispatcher) {
            context = ApplicationProvider.getApplicationContext()
            val workerParams: WorkerParameters = mock()
            val deps =
                UsageWorkerDependencies(
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
            worker = UsageCheckWorker(context, workerParams, deps, testDispatcher)
            whenever(trafficTracker.status).thenReturn(MutableStateFlow(TrackingStatus.Active))
            whenever(trafficTracker.updateTrafficUsage(any())).thenReturn(TrafficUpdateOutcome.Completed)
        }

    private suspend fun setupDefaultMocks() {
        whenever(timeProvider.now()).thenReturn(1_700_000_000_000L)
        whenever(timeProvider.zoneId()).thenReturn(ZoneId.of("UTC"))
        whenever(permissionManager.hasNotificationPermission()).thenReturn(true)
        whenever(notificationHelper.sendUsageNotification(any(), any(), any(), any())).thenReturn(true)
        whenever(appSettingsRepository.getAppSettings())
            .thenReturn(flowOf(AppSettings(notifyLowMinutes = true, notifyLowTraffic = true, isLoaded = true)))
        whenever(simNotificationFlagsRepository.getNotifiedExhaustedMinutes(any())).thenReturn(flowOf(false))
        whenever(simNotificationFlagsRepository.getNotifiedLowMinutes(any())).thenReturn(flowOf(false))
        whenever(simNotificationFlagsRepository.getNotifiedCriticalMinutes(any())).thenReturn(flowOf(false))
        whenever(simNotificationFlagsRepository.getNotifiedExhaustedTraffic(any())).thenReturn(flowOf(false))
        whenever(simNotificationFlagsRepository.getNotifiedLowTraffic(any())).thenReturn(flowOf(false))
        whenever(simNotificationFlagsRepository.getNotifiedCriticalTraffic(any())).thenReturn(flowOf(false))
    }

    private fun createUsageSnapshot(
        minutesUsed: Int = 0,
        minutesLimit: Int = 0,
        identity: String = "sim_0",
        autoResetDay: Int = 1,
        autoReset: Boolean = true,
    ): GlobalSnapshot {
        val entity =
            SemanticEntity(
                id = "0_minutes",
                simSlot = 0,
                operatorName = "MTS",
                packageType = PackageType.MINUTES,
                used = minutesUsed.toLong(),
                limit = minutesLimit.toLong(),
                remaining = (minutesLimit - minutesUsed).toLong(),
                unit = MeasurementUnit.MINUTES,
                periodStart = 0L,
                periodEnd = 0L,
                lastUpdateTime = 0L,
                sourceType = DataSourceType.SYSTEM,
            )
        val simSnapshot =
            SimSnapshot(
                slot = 0,
                identity = identity,
                operatorName = "MTS",
                entities = mapOf(PackageType.MINUTES to entity),
                autoResetDay = autoResetDay,
                autoReset = autoReset,
            )
        return GlobalSnapshot(
            version = SchemaVersion(1, 0, 0),
            timestamp = 1000L,
            simSnapshots = listOf(simSnapshot),
            capabilities = emptyList(),
        )
    }

    private fun createTrafficSnapshot(
        used: Long,
        remaining: Long,
        autoResetDay: Int = 1,
        autoReset: Boolean = true,
    ): GlobalSnapshot {
        val limit = 1000L * 1024 * 1024
        val identity = "sim_0"
        val entity =
            SemanticEntity(
                id = "0_traffic",
                simSlot = 0,
                operatorName = "MTS",
                packageType = PackageType.DATA,
                used = used,
                limit = limit,
                remaining = remaining,
                unit = MeasurementUnit.BYTES,
                periodStart = 0L,
                periodEnd = 0L,
                lastUpdateTime = 0L,
                sourceType = DataSourceType.SYSTEM,
            )
        val simSnapshot =
            SimSnapshot(
                slot = 0,
                identity = identity,
                operatorName = "MTS",
                entities = mapOf(PackageType.DATA to entity),
                autoResetDay = autoResetDay,
                autoReset = autoReset,
            )
        return GlobalSnapshot(
            version = SchemaVersion(1, 0, 0),
            timestamp = 1000L,
            simSnapshots = listOf(simSnapshot),
            capabilities = emptyList(),
        )
    }

    @Test
    fun `doWork returns success when no snapshots`() =
        runTest(testDispatcher) {
            val emptySnapshot =
                GlobalSnapshot(
                    version = SchemaVersion(1, 0, 0),
                    timestamp = 0L,
                    simSnapshots = emptyList(),
                    capabilities = emptyList(),
                )
            whenever(usageStateRepository.refresh()).thenReturn(RefreshResult.Timeout(emptySnapshot))
            val result = worker.doWork()
            assertEquals(ListenableWorker.Result.retry(), result)
        }

    @Test
    fun `doWork returns retry when refresh fails`() =
        runTest(testDispatcher) {
            val emptySnapshot =
                GlobalSnapshot(
                    version = SchemaVersion(1, 0, 0),
                    timestamp = 0L,
                    simSnapshots = emptyList(),
                    capabilities = emptyList(),
                )
            val exception = RuntimeException("Refresh failed")
            whenever(usageStateRepository.refresh()).thenReturn(RefreshResult.Failure(emptySnapshot, exception))
            val result = worker.doWork()
            assertEquals(ListenableWorker.Result.retry(), result)
        }

    @Test
    fun `doWork sends notification when minutes low`() =
        runTest(testDispatcher) {
            setupDefaultMocks()
            val snapshot = createUsageSnapshot(minutesUsed = 81, minutesLimit = 100)

            whenever(usageStateRepository.refresh()).thenReturn(RefreshResult.Success(snapshot))

            worker.doWork()

            verify(notificationHelper).sendUsageNotification(any(), any(), eq(0), eq(UsageNotificationType.MINUTES))
        }

    @Test
    fun `notification when system clock is un-synchronized does not contain days text`() =
        runTest(testDispatcher) {
            setupDefaultMocks()
            val unSynchronizedNowMs = 1_000L // Below MIN_VALID_TIMESTAMP
            whenever(timeProvider.now()).thenReturn(unSynchronizedNowMs)

            val snapshot =
                createUsageSnapshot(
                    minutesUsed = 85,
                    minutesLimit = 100,
                    identity = "sim_unsynced_time",
                    autoReset = true,
                )
            whenever(usageStateRepository.refresh()).thenReturn(RefreshResult.Success(snapshot))

            val validNowMs = 1_700_000_000_000L
            val expectedDaysRemaining =
                DateUtils.getDaysRemainingInMonth(
                    1,
                    validNowMs,
                    timeProvider.zoneId(),
                )
            val expectedDaysText = FormatUtils.formatDaysPlural(context, expectedDaysRemaining)

            worker.doWork()

            verify(notificationHelper).sendUsageNotification(
                title = any(),
                message = argThat { !contains(expectedDaysText) },
                simSlot = eq(0),
                type = eq(UsageNotificationType.MINUTES),
            )
        }

    @Test
    fun `daysRemaining in notification corresponds to autoResetDay from simSnapshot`() =
        runTest(testDispatcher) {
            setupDefaultMocks()
            val nowMs = 1_700_000_000_000L
            whenever(timeProvider.now()).thenReturn(nowMs)

            val customAutoResetDay = 15
            val snapshot =
                createUsageSnapshot(
                    minutesUsed = 85,
                    minutesLimit = 100,
                    identity = "sim_custom_reset",
                    autoResetDay = customAutoResetDay,
                )
            whenever(usageStateRepository.refresh()).thenReturn(RefreshResult.Success(snapshot))

            val expectedDaysRemaining =
                DateUtils.getDaysRemainingInMonth(
                    customAutoResetDay,
                    nowMs,
                    timeProvider.zoneId(),
                )
            val expectedDaysText = FormatUtils.formatDaysPlural(context, expectedDaysRemaining)

            worker.doWork()

            verify(notificationHelper).sendUsageNotification(
                title = any(),
                message = argThat { contains(expectedDaysText) },
                simSlot = eq(0),
                type = eq(UsageNotificationType.MINUTES),
            )
        }

    @Test
    fun `low minutes notification when autoReset is false does not contain days text`() =
        runTest(testDispatcher) {
            setupDefaultMocks()
            val nowMs = 1_700_000_000_000L
            whenever(timeProvider.now()).thenReturn(nowMs)

            val snapshot =
                createUsageSnapshot(
                    minutesUsed = 85,
                    minutesLimit = 100,
                    identity = "sim_no_reset",
                    autoReset = false,
                )
            whenever(usageStateRepository.refresh()).thenReturn(RefreshResult.Success(snapshot))

            val expectedDaysRemaining =
                DateUtils.getDaysRemainingInMonth(
                    1,
                    nowMs,
                    timeProvider.zoneId(),
                )
            val expectedDaysText = FormatUtils.formatDaysPlural(context, expectedDaysRemaining)

            worker.doWork()

            verify(notificationHelper).sendUsageNotification(
                title = any(),
                message = argThat { !contains(expectedDaysText) },
                simSlot = eq(0),
                type = eq(UsageNotificationType.MINUTES),
            )
        }

    @Test
    fun `exhausted minutes notification when autoReset is false uses no period message`() =
        runTest(testDispatcher) {
            setupDefaultMocks()
            val snapshot =
                createUsageSnapshot(
                    minutesUsed = 100,
                    minutesLimit = 100,
                    identity = "sim_no_reset_exhausted",
                    autoReset = false,
                )
            whenever(usageStateRepository.refresh()).thenReturn(RefreshResult.Success(snapshot))

            worker.doWork()

            val expectedMessage = context.getString(R.string.notification_minutes_exhausted_body_no_period)
            verify(notificationHelper).sendUsageNotification(
                title = any(),
                message = eq(expectedMessage),
                simSlot = eq(0),
                type = eq(UsageNotificationType.MINUTES),
            )
        }

    @Test
    fun `traffic critical notification uses critical level`() =
        runTest(testDispatcher) {
            setupTrafficCriticalScenario()

            worker.doWork()

            verifyTrafficCriticalNotification()
        }

    private suspend fun setupTrafficCriticalScenario() {
        setupDefaultMocks()
        val used = 950L * 1024 * 1024
        val remaining = 50L * 1024 * 1024

        val snapshot = createTrafficSnapshot(used, remaining)

        whenever(usageStateRepository.refresh()).thenReturn(RefreshResult.Success(snapshot))
    }

    private fun verifyTrafficCriticalNotification() {
        val criticalLabel = context.getString(R.string.usage_level_critical)
        verify(notificationHelper).sendUsageNotification(
            title = any(),
            message = argThat { contains(criticalLabel) },
            simSlot = eq(0),
            type = eq(UsageNotificationType.TRAFFIC),
        )
    }

    @Test
    fun `doWork skips notifications when settings load fails`() =
        runTest(testDispatcher) {
            setupDefaultMocks()
            val snapshot = createUsageSnapshot(minutesUsed = 81, minutesLimit = 100)

            whenever(usageStateRepository.refresh()).thenReturn(RefreshResult.Success(snapshot))
            whenever(appSettingsRepository.getAppSettings()).thenReturn(MutableSharedFlow())

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(notificationHelper, never()).sendUsageNotification(any(), any(), any(), any())
        }

    @Test
    fun `doWork returns retry when traffic update status is timeout`() =
        runTest(testDispatcher) {
            setupDefaultMocks()
            whenever(trafficTracker.updateTrafficUsage()).thenReturn(TrafficUpdateOutcome.TimedOut(0L))

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
            verify(usageStateRepository, never()).refresh()
        }

    @Test
    fun `doWork continues when traffic update status is active`() =
        runTest(testDispatcher) {
            setupDefaultMocks()
            whenever(trafficTracker.status).thenReturn(
                MutableStateFlow(TrackingStatus.Active),
            )
            val emptySnapshot =
                GlobalSnapshot(
                    version = SchemaVersion(1, 0, 0),
                    timestamp = 0L,
                    simSnapshots = emptyList(),
                    capabilities = emptyList(),
                )
            whenever(usageStateRepository.refresh()).thenReturn(RefreshResult.Timeout(emptySnapshot))

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
            verify(trafficTracker).updateTrafficUsage()
        }

    @Test
    fun `flags are not set when notification delivery fails`() =
        runTest(testDispatcher) {
            setupDefaultMocks()
            whenever(notificationHelper.sendUsageNotification(any(), any(), any(), any())).thenReturn(false)
            val snapshot = createUsageSnapshot(minutesUsed = 100, minutesLimit = 100, identity = "sim_0")
            whenever(usageStateRepository.refresh()).thenReturn(RefreshResult.Success(snapshot))

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(simNotificationFlagsRepository, never()).setNotifiedExhaustedMinutes(eq("sim_0"), eq(true))
        }

    @Test
    fun `reset stale minutes flags when notifyLowMinutes is false`() =
        runTest(testDispatcher) {
            setupDefaultMocks()
            whenever(permissionManager.hasNotificationPermission()).thenReturn(true)
            whenever(appSettingsRepository.getAppSettings())
                .thenReturn(flowOf(AppSettings(notifyLowMinutes = false, notifyLowTraffic = true, isLoaded = true)))
            val identity = "sim_stale_min"
            whenever(simNotificationFlagsRepository.getNotifiedLowMinutes(identity)).thenReturn(flowOf(true))
            whenever(simNotificationFlagsRepository.getNotifiedCriticalMinutes(identity)).thenReturn(flowOf(true))
            whenever(simNotificationFlagsRepository.getNotifiedExhaustedMinutes(identity)).thenReturn(flowOf(true))
            whenever(simNotificationFlagsRepository.getNotifiedLowTraffic(identity)).thenReturn(flowOf(false))
            whenever(simNotificationFlagsRepository.getNotifiedCriticalTraffic(identity)).thenReturn(flowOf(false))
            whenever(simNotificationFlagsRepository.getNotifiedExhaustedTraffic(identity)).thenReturn(flowOf(false))

            val snapshot = createUsageSnapshot(minutesUsed = 85, minutesLimit = 100, identity = identity)
            whenever(usageStateRepository.refresh()).thenReturn(RefreshResult.Success(snapshot))

            worker.doWork()

            verify(simNotificationFlagsRepository).setNotifiedLowMinutes(identity, false)
            verify(simNotificationFlagsRepository).setNotifiedCriticalMinutes(identity, false)
            verify(simNotificationFlagsRepository).setNotifiedExhaustedMinutes(identity, false)
            verify(notificationHelper, never()).sendUsageNotification(any(), any(), any(), any())
            verify(simNotificationFlagsRepository, never()).setNotifiedLowTraffic(any(), any())
            verify(simNotificationFlagsRepository, never()).setNotifiedCriticalTraffic(any(), any())
            verify(simNotificationFlagsRepository, never()).setNotifiedExhaustedTraffic(any(), any())
        }

    @Test
    fun `reset stale minutes flags when limit is zero`() =
        runTest(testDispatcher) {
            setupDefaultMocks()
            whenever(permissionManager.hasNotificationPermission()).thenReturn(true)
            whenever(appSettingsRepository.getAppSettings())
                .thenReturn(flowOf(AppSettings(notifyLowMinutes = true, notifyLowTraffic = true, isLoaded = true)))
            val identity = "sim_zero_limit"
            whenever(simNotificationFlagsRepository.getNotifiedLowMinutes(identity)).thenReturn(flowOf(true))
            whenever(simNotificationFlagsRepository.getNotifiedCriticalMinutes(identity)).thenReturn(flowOf(true))
            whenever(simNotificationFlagsRepository.getNotifiedExhaustedMinutes(identity)).thenReturn(flowOf(true))
            whenever(simNotificationFlagsRepository.getNotifiedLowTraffic(identity)).thenReturn(flowOf(false))
            whenever(simNotificationFlagsRepository.getNotifiedCriticalTraffic(identity)).thenReturn(flowOf(false))
            whenever(simNotificationFlagsRepository.getNotifiedExhaustedTraffic(identity)).thenReturn(flowOf(false))

            val snapshot = createUsageSnapshot(minutesUsed = 0, minutesLimit = 0, identity = identity)
            whenever(usageStateRepository.refresh()).thenReturn(RefreshResult.Success(snapshot))

            worker.doWork()

            verify(simNotificationFlagsRepository).setNotifiedLowMinutes(identity, false)
            verify(simNotificationFlagsRepository).setNotifiedCriticalMinutes(identity, false)
            verify(simNotificationFlagsRepository).setNotifiedExhaustedMinutes(identity, false)
            verify(notificationHelper, never()).sendUsageNotification(any(), any(), any(), any())
        }

    @Test
    fun `reset stale traffic flags when notifyLowTraffic is false`() =
        runTest(testDispatcher) {
            setupDefaultMocks()
            whenever(permissionManager.hasNotificationPermission()).thenReturn(true)
            whenever(appSettingsRepository.getAppSettings())
                .thenReturn(flowOf(AppSettings(notifyLowMinutes = true, notifyLowTraffic = false, isLoaded = true)))
            val identity = "sim_0"
            whenever(simNotificationFlagsRepository.getNotifiedLowMinutes(identity)).thenReturn(flowOf(false))
            whenever(simNotificationFlagsRepository.getNotifiedCriticalMinutes(identity)).thenReturn(flowOf(false))
            whenever(simNotificationFlagsRepository.getNotifiedExhaustedMinutes(identity)).thenReturn(flowOf(false))
            whenever(simNotificationFlagsRepository.getNotifiedLowTraffic(identity)).thenReturn(flowOf(true))
            whenever(simNotificationFlagsRepository.getNotifiedCriticalTraffic(identity)).thenReturn(flowOf(true))
            whenever(simNotificationFlagsRepository.getNotifiedExhaustedTraffic(identity)).thenReturn(flowOf(true))

            val snapshot =
                createTrafficSnapshot(
                    used = 800L * 1024 * 1024,
                    remaining = 200L * 1024 * 1024,
                )
            whenever(usageStateRepository.refresh()).thenReturn(RefreshResult.Success(snapshot))

            worker.doWork()

            verify(simNotificationFlagsRepository).setNotifiedLowTraffic(identity, false)
            verify(simNotificationFlagsRepository).setNotifiedCriticalTraffic(identity, false)
            verify(simNotificationFlagsRepository).setNotifiedExhaustedTraffic(identity, false)
            verify(notificationHelper, never()).sendUsageNotification(any(), any(), any(), any())
            verify(simNotificationFlagsRepository, never()).setNotifiedLowMinutes(any(), any())
            verify(simNotificationFlagsRepository, never()).setNotifiedCriticalMinutes(any(), any())
            verify(simNotificationFlagsRepository, never()).setNotifiedExhaustedMinutes(any(), any())
        }

    @Test
    fun `doWork does not call setters when flags are already false`() =
        runTest(testDispatcher) {
            setupDefaultMocks()
            val snapshot = createUsageSnapshot(minutesUsed = 0, minutesLimit = 0, identity = "sim_0")
            whenever(usageStateRepository.refresh()).thenReturn(RefreshResult.Success(snapshot))

            worker.doWork()

            verify(simNotificationFlagsRepository, never()).setNotifiedLowMinutes(any(), any())
            verify(simNotificationFlagsRepository, never()).setNotifiedCriticalMinutes(any(), any())
            verify(simNotificationFlagsRepository, never()).setNotifiedExhaustedMinutes(any(), any())
        }

    @Test
    fun `flag read timeout skips write without crashing`() =
        runTest(testDispatcher) {
            whenever(permissionManager.hasNotificationPermission()).thenReturn(true)
            whenever(appSettingsRepository.getAppSettings())
                .thenReturn(flowOf(AppSettings(notifyLowMinutes = false, notifyLowTraffic = true, isLoaded = true)))
            val identity = "sim_timeout"
            whenever(
                simNotificationFlagsRepository.getNotifiedLowMinutes(identity),
            ).thenReturn(kotlinx.coroutines.flow.flow { })
            whenever(simNotificationFlagsRepository.getNotifiedCriticalMinutes(identity)).thenReturn(flowOf(false))
            whenever(simNotificationFlagsRepository.getNotifiedExhaustedMinutes(identity)).thenReturn(flowOf(false))

            val snapshot = createUsageSnapshot(minutesUsed = 0, minutesLimit = 0, identity = identity)
            whenever(usageStateRepository.refresh()).thenReturn(RefreshResult.Success(snapshot))

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(simNotificationFlagsRepository, never()).setNotifiedLowMinutes(eq(identity), any())
        }
}
