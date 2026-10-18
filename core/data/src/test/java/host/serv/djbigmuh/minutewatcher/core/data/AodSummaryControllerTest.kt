package host.serv.djbigmuh.minutewatcher.core.data

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ApplicationProvider
import host.serv.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import host.serv.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import host.serv.djbigmuh.minutewatcher.core.domain.PermissionRepository
import host.serv.djbigmuh.minutewatcher.core.domain.UsageStateRepository
import host.serv.djbigmuh.minutewatcher.core.model.AppSettings
import host.serv.djbigmuh.minutewatcher.core.model.Constants
import host.serv.djbigmuh.minutewatcher.core.model.DataSourceType
import host.serv.djbigmuh.minutewatcher.core.model.GlobalSnapshot
import host.serv.djbigmuh.minutewatcher.core.model.MeasurementUnit
import host.serv.djbigmuh.minutewatcher.core.model.PackageType
import host.serv.djbigmuh.minutewatcher.core.model.SchemaVersion
import host.serv.djbigmuh.minutewatcher.core.model.SemanticEntity
import host.serv.djbigmuh.minutewatcher.core.model.SimSnapshot
import host.serv.djbigmuh.minutewatcher.core.resources.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AodSummaryControllerTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val testScope = TestScope(mainDispatcherRule.testDispatcher)
    private lateinit var context: Context

    private val usageStateRepository: UsageStateRepository = mock()
    private val appSettingsRepository: AppSettingsRepository = mock()
    private val permissionRepository: PermissionRepository = mock()
    private val notificationHelper: NotificationHelper = mock()

    private val snapshotFlow = MutableStateFlow(createGlobalSnapshot())
    private val appSettingsFlow = MutableStateFlow(AppSettings())
    private val permissionFlow = MutableStateFlow(true)

    private lateinit var controller: AodSummaryController

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()

        whenever(usageStateRepository.observeSnapshot()).thenReturn(snapshotFlow)
        whenever(appSettingsRepository.observeAppSettings()).thenReturn(appSettingsFlow)
        whenever(permissionRepository.notificationPermissionGranted).thenReturn(permissionFlow)
        whenever(notificationHelper.isAodSummaryAvailable()).thenReturn(true)
        whenever(notificationHelper.isAodSummaryNotificationActive()).thenReturn(true)
        whenever(notificationHelper.sendAodSummaryNotification(any(), any(), any())).thenReturn(true)

        controller =
            AodSummaryController(
                usageStateRepository = usageStateRepository,
                appSettingsRepository = appSettingsRepository,
                permissionRepository = permissionRepository,
                notificationHelper = notificationHelper,
                context = context,
                scope = testScope,
                ioDispatcher = mainDispatcherRule.testDispatcher,
            )
    }

    @Test
    fun `setting disabled does not send notification`() =
        testScope.runTest {
            appSettingsFlow.value = AppSettings(aodSummaryEnabled = false)
            controller.start()
            testScheduler.advanceTimeBy(150L)

            verify(notificationHelper, never()).sendAodSummaryNotification(any(), any(), any())
            withTimeout(5.seconds) {
                controller.observationJob?.cancelAndJoin()
            }
        }

    @Test
    fun `setting enabled with valid SIMs posts notification sorted by slot`() =
        testScope.runTest {
            appSettingsFlow.value = AppSettings(aodSummaryEnabled = true)

            val sim0 = createSimSnapshot(0, "MTS", minutesLimit = 100, minutesRemaining = 50)
            val sim1 =
                createSimSnapshot(
                    slot = 1,
                    operatorName = "Beeline",
                    minutesLimit = 200,
                    minutesRemaining = 120,
                    trafficLimit = 10 * Constants.BYTES_PER_GB,
                    trafficRemainingBytes = 5 * Constants.BYTES_PER_GB,
                )
            snapshotFlow.value = createGlobalSnapshot(timestamp = 1000L, sims = listOf(sim1, sim0))

            controller.start()
            testScheduler.advanceTimeBy(150L)

            val titleCaptor = argumentCaptor<String>()
            val lineCaptor = argumentCaptor<String>()
            val expandedCaptor = argumentCaptor<String>()

            verify(notificationHelper).sendAodSummaryNotification(
                titleCaptor.capture(),
                lineCaptor.capture(),
                expandedCaptor.capture(),
            )

            assertEquals(context.getString(R.string.app_name), titleCaptor.firstValue)

            val expectedLines = expandedCaptor.firstValue.split("\n")
            assertEquals(2, expectedLines.size)
            assertEquals(expectedLines.first(), lineCaptor.firstValue)

            val line0 = expectedLines[0]
            val line1 = expectedLines[1]

            assertEquals("MTS • 50 minutes", line0)
            assertEquals("Beeline • 120 minutes • 5.0 GB", line1)

            withTimeout(5.seconds) {
                controller.observationJob?.cancelAndJoin()
            }
        }

    @Test
    fun `SIM without limits is excluded and empty lines cancels summary`() =
        testScope.runTest {
            appSettingsFlow.value = AppSettings(aodSummaryEnabled = true)
            val simNoLimits = createSimSnapshot(0, "MTS")
            snapshotFlow.value = createGlobalSnapshot(timestamp = 1000L, sims = listOf(simNoLimits))

            controller.start()
            testScheduler.advanceTimeBy(150L)

            verify(notificationHelper, never()).sendAodSummaryNotification(any(), any(), any())
            withTimeout(5.seconds) {
                controller.observationJob?.cancelAndJoin()
            }
        }

    @Test
    fun `empty timestamp snapshot cancels summary if posted`() =
        testScope.runTest {
            appSettingsFlow.value = AppSettings(aodSummaryEnabled = true)
            val sim0 = createSimSnapshot(0, "MTS", minutesLimit = 100, minutesRemaining = 50)

            snapshotFlow.value = createGlobalSnapshot(timestamp = 1000L, sims = listOf(sim0))
            controller.start()
            testScheduler.advanceTimeBy(150L)
            verify(notificationHelper, times(1)).sendAodSummaryNotification(any(), any(), any())

            snapshotFlow.value = createGlobalSnapshot(timestamp = Constants.Data.EMPTY_TIMESTAMP, sims = listOf(sim0))
            testScheduler.advanceTimeBy(150L)

            verify(notificationHelper, times(1)).cancelAodSummaryNotification()
            withTimeout(5.seconds) {
                controller.observationJob?.cancelAndJoin()
            }
        }

    @Test
    fun `permission flow value change re-evaluates aod summary availability`() =
        testScope.runTest {
            appSettingsFlow.value = AppSettings(aodSummaryEnabled = true)
            val sim0 = createSimSnapshot(0, "MTS", minutesLimit = 100, minutesRemaining = 50)

            snapshotFlow.value = createGlobalSnapshot(timestamp = 1000L, sims = listOf(sim0))
            controller.start()
            testScheduler.advanceTimeBy(150L)

            verify(notificationHelper, times(1)).sendAodSummaryNotification(any(), any(), any())
            verify(notificationHelper, times(1)).isAodSummaryAvailable()

            whenever(notificationHelper.isAodSummaryAvailable()).thenReturn(false)
            permissionFlow.value = false
            testScheduler.advanceTimeBy(150L)

            verify(notificationHelper, times(2)).isAodSummaryAvailable()
            verify(notificationHelper, times(1)).cancelAodSummaryNotification()
            withTimeout(5.seconds) {
                controller.observationJob?.cancelAndJoin()
            }
        }

    @Test
    fun `stale permission flow false with live availability true posts summary`() =
        testScope.runTest {
            appSettingsFlow.value = AppSettings(aodSummaryEnabled = true)
            permissionFlow.value = false
            whenever(notificationHelper.isAodSummaryAvailable()).thenReturn(true)
            val sim0 = createSimSnapshot(0, "MTS", minutesLimit = 100, minutesRemaining = 50)
            snapshotFlow.value = createGlobalSnapshot(timestamp = 1000L, sims = listOf(sim0))

            controller.start()
            testScheduler.advanceTimeBy(150L)

            verify(notificationHelper, times(1)).sendAodSummaryNotification(any(), any(), any())
            withTimeout(5.seconds) {
                controller.observationJob?.cancelAndJoin()
            }
        }

    @Test
    fun `live availability turning false cancels posted summary`() =
        testScope.runTest {
            appSettingsFlow.value = AppSettings(aodSummaryEnabled = true)
            val sim0 = createSimSnapshot(0, "MTS", minutesLimit = 100, minutesRemaining = 50)
            snapshotFlow.value = createGlobalSnapshot(timestamp = 1000L, sims = listOf(sim0))

            controller.start()
            testScheduler.advanceTimeBy(150L)

            verify(notificationHelper, times(1)).sendAodSummaryNotification(any(), any(), any())

            whenever(notificationHelper.isAodSummaryAvailable()).thenReturn(false)
            snapshotFlow.value = createGlobalSnapshot(timestamp = 2000L, sims = listOf(sim0))
            testScheduler.advanceTimeBy(150L)

            verify(notificationHelper, times(1)).cancelAodSummaryNotification()
            withTimeout(5.seconds) {
                controller.observationJob?.cancelAndJoin()
            }
        }

    @Test
    fun `channel unavailability cancels posted summary`() =
        testScope.runTest {
            appSettingsFlow.value = AppSettings(aodSummaryEnabled = true)
            val sim0 = createSimSnapshot(0, "MTS", minutesLimit = 100, minutesRemaining = 50)

            snapshotFlow.value = createGlobalSnapshot(timestamp = 1000L, sims = listOf(sim0))
            controller.start()
            testScheduler.advanceTimeBy(150L)

            verify(notificationHelper, times(1)).sendAodSummaryNotification(any(), any(), any())

            whenever(notificationHelper.isAodSummaryAvailable()).thenReturn(false)
            snapshotFlow.value = createGlobalSnapshot(timestamp = 2000L, sims = listOf(sim0))
            testScheduler.advanceTimeBy(150L)

            verify(notificationHelper, times(1)).cancelAodSummaryNotification()
            withTimeout(5.seconds) {
                controller.observationJob?.cancelAndJoin()
            }
        }

    @Test
    fun `duplicate text prevents re-sending notification`() =
        testScope.runTest {
            appSettingsFlow.value = AppSettings(aodSummaryEnabled = true)
            val sim0 = createSimSnapshot(0, "MTS", minutesLimit = 100, minutesRemaining = 50)

            snapshotFlow.value = createGlobalSnapshot(timestamp = 1000L, sims = listOf(sim0))
            controller.start()
            testScheduler.advanceTimeBy(150L)

            snapshotFlow.value = createGlobalSnapshot(timestamp = 2000L, sims = listOf(sim0))
            testScheduler.advanceTimeBy(150L)

            verify(notificationHelper, times(1)).sendAodSummaryNotification(any(), any(), any())
            withTimeout(5.seconds) {
                controller.observationJob?.cancelAndJoin()
            }
        }

    @Test
    fun `changed text triggers new send`() =
        testScope.runTest {
            appSettingsFlow.value = AppSettings(aodSummaryEnabled = true)
            val sim0 = createSimSnapshot(0, "MTS", minutesLimit = 100, minutesRemaining = 50)

            snapshotFlow.value = createGlobalSnapshot(timestamp = 1000L, sims = listOf(sim0))
            controller.start()
            testScheduler.advanceTimeBy(150L)

            val sim0Updated = createSimSnapshot(0, "MTS", minutesLimit = 100, minutesRemaining = 40)
            snapshotFlow.value = createGlobalSnapshot(timestamp = 2000L, sims = listOf(sim0Updated))
            testScheduler.advanceTimeBy(150L)

            verify(notificationHelper, times(2)).sendAodSummaryNotification(any(), any(), any())
            withTimeout(5.seconds) {
                controller.observationJob?.cancelAndJoin()
            }
        }

    @Test
    fun `aodSummaryEnabled false cancels posted summary and re-enabling resends`() =
        testScope.runTest {
            appSettingsFlow.value = AppSettings(aodSummaryEnabled = true)
            val sim0 = createSimSnapshot(0, "MTS", minutesLimit = 100, minutesRemaining = 50)

            snapshotFlow.value = createGlobalSnapshot(timestamp = 1000L, sims = listOf(sim0))
            controller.start()
            testScheduler.advanceTimeBy(150L)

            appSettingsFlow.value = AppSettings(aodSummaryEnabled = false)
            testScheduler.advanceTimeBy(150L)

            verify(notificationHelper, times(1)).cancelAodSummaryNotification()

            appSettingsFlow.value = AppSettings(aodSummaryEnabled = true)
            testScheduler.advanceTimeBy(150L)

            verify(notificationHelper, times(2)).sendAodSummaryNotification(any(), any(), any())
            withTimeout(5.seconds) {
                controller.observationJob?.cancelAndJoin()
            }
        }

    @Test
    fun `calling start multiple times is idempotent and does not duplicate observers`() =
        testScope.runTest {
            appSettingsFlow.value = AppSettings(aodSummaryEnabled = true)
            val sim0 = createSimSnapshot(0, "MTS", minutesLimit = 100, minutesRemaining = 50)

            snapshotFlow.value = createGlobalSnapshot(timestamp = 1000L, sims = listOf(sim0))
            controller.start()
            val initialJob = controller.observationJob
            controller.start()
            assertEquals(initialJob, controller.observationJob)
            testScheduler.advanceTimeBy(150L)

            verify(notificationHelper, times(1)).sendAodSummaryNotification(any(), any(), any())
            withTimeout(5.seconds) {
                controller.observationJob?.cancelAndJoin()
            }
        }

    @Test
    fun `exception during send does not kill observer and subsequent update retries`() =
        testScope.runTest {
            appSettingsFlow.value = AppSettings(aodSummaryEnabled = true)
            val sim0 = createSimSnapshot(0, "MTS", minutesLimit = 100, minutesRemaining = 50)

            snapshotFlow.value = createGlobalSnapshot(timestamp = 1000L, sims = listOf(sim0))
            whenever(
                notificationHelper.sendAodSummaryNotification(any(), any(), any()),
            ).thenThrow(RuntimeException("Transient error"))

            controller.start()
            testScheduler.advanceTimeBy(150L)

            whenever(
                notificationHelper.sendAodSummaryNotification(any(), any(), any()),
            ).thenReturn(true)

            val sim0Updated = createSimSnapshot(0, "MTS", minutesLimit = 100, minutesRemaining = 40)
            snapshotFlow.value = createGlobalSnapshot(timestamp = 2000L, sims = listOf(sim0Updated))
            testScheduler.advanceTimeBy(150L)

            verify(notificationHelper, times(2)).sendAodSummaryNotification(any(), any(), any())
            withTimeout(5.seconds) {
                controller.observationJob?.cancelAndJoin()
            }
        }

    @Test
    fun `summary lines format strings according to app locale`() =
        testScope.runTest {
            val russianLocale = Locale.forLanguageTag("ru")
            val localeList = LocaleListCompat.create(russianLocale)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val localeManager = context.getSystemService(LocaleManager::class.java)
                if (localeManager != null) {
                    localeManager.applicationLocales = LocaleList(russianLocale)
                }
            }
            AppCompatDelegate.setApplicationLocales(localeList)

            try {
                appSettingsFlow.value = AppSettings(aodSummaryEnabled = true)

                val sim0 = createSimSnapshot(0, "MTS", minutesLimit = 100, minutesRemaining = 5)
                snapshotFlow.value = createGlobalSnapshot(timestamp = 1000L, sims = listOf(sim0))

                controller.start()
                testScheduler.advanceTimeBy(150L)

                val lineCaptor = argumentCaptor<String>()
                verify(notificationHelper).sendAodSummaryNotification(
                    any(),
                    lineCaptor.capture(),
                    any(),
                )

                assertTrue(
                    "Summary line should contain Russian plural 'минут'",
                    lineCaptor.firstValue.contains("минут"),
                )
            } finally {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val localeManager = context.getSystemService(LocaleManager::class.java)
                    if (localeManager != null) {
                        localeManager.applicationLocales = LocaleList.getEmptyLocaleList()
                    }
                }
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
                withTimeout(5.seconds) {
                    controller.observationJob?.cancelAndJoin()
                }
            }
        }

    @Test
    fun `reposts summary when notification was externally removed even if content is unchanged`() =
        testScope.runTest {
            appSettingsFlow.value = AppSettings(aodSummaryEnabled = true)
            val sim0 = createSimSnapshot(0, "MTS", minutesLimit = 100, minutesRemaining = 50)
            snapshotFlow.value = createGlobalSnapshot(timestamp = 1000L, sims = listOf(sim0))

            controller.start()
            testScheduler.advanceTimeBy(150L)

            verify(notificationHelper, times(1)).sendAodSummaryNotification(any(), any(), any())

            whenever(notificationHelper.isAodSummaryNotificationActive()).thenReturn(false)
            snapshotFlow.value = createGlobalSnapshot(timestamp = 2000L, sims = listOf(sim0)).copy(revision = 1L)
            testScheduler.advanceTimeBy(150L)

            verify(notificationHelper, times(2)).sendAodSummaryNotification(any(), any(), any())
            withTimeout(5.seconds) {
                controller.observationJob?.cancelAndJoin()
            }
        }

    @Test
    fun `cancels summary on start if notification is active but settings or permission became unavailable`() =
        testScope.runTest {
            appSettingsFlow.value = AppSettings(aodSummaryEnabled = false)
            whenever(notificationHelper.isAodSummaryNotificationActive()).thenReturn(true)

            controller.start()
            testScheduler.advanceTimeBy(150L)

            verify(notificationHelper).cancelAodSummaryNotification()
            withTimeout(5.seconds) {
                controller.observationJob?.cancelAndJoin()
            }
        }

    @Test
    fun `heartbeat tick reposts summary when externally removed and content is unchanged`() =
        testScope.runTest {
            appSettingsFlow.value = AppSettings(aodSummaryEnabled = true)
            val sim0 = createSimSnapshot(0, "MTS", minutesLimit = 100, minutesRemaining = 50)
            snapshotFlow.value = createGlobalSnapshot(timestamp = 1000L, sims = listOf(sim0))

            controller.start()
            testScheduler.advanceTimeBy(150L)

            verify(notificationHelper, times(1)).sendAodSummaryNotification(any(), any(), any())

            whenever(notificationHelper.isAodSummaryNotificationActive()).thenReturn(false)

            testScheduler.advanceTimeBy(Constants.Aod.HEARTBEAT_INTERVAL_MS - 1000L)
            verify(notificationHelper, times(1)).sendAodSummaryNotification(any(), any(), any())

            testScheduler.advanceTimeBy(1000L)

            verify(notificationHelper, times(2)).sendAodSummaryNotification(any(), any(), any())
            withTimeout(5.seconds) {
                controller.observationJob?.cancelAndJoin()
            }
        }

    @Test
    fun `heartbeat ticks do not trigger re-posts when notification is active and content unchanged`() =
        testScope.runTest {
            appSettingsFlow.value = AppSettings(aodSummaryEnabled = true)
            val sim0 = createSimSnapshot(0, "MTS", minutesLimit = 100, minutesRemaining = 50)
            snapshotFlow.value = createGlobalSnapshot(timestamp = 1000L, sims = listOf(sim0))

            controller.start()
            testScheduler.advanceTimeBy(150L)

            verify(notificationHelper, times(1)).sendAodSummaryNotification(any(), any(), any())

            whenever(notificationHelper.isAodSummaryNotificationActive()).thenReturn(true)

            testScheduler.advanceTimeBy(Constants.Aod.HEARTBEAT_INTERVAL_MS * 3)

            verify(notificationHelper, times(1)).sendAodSummaryNotification(any(), any(), any())
            withTimeout(5.seconds) {
                controller.observationJob?.cancelAndJoin()
            }
        }

    @Test
    fun `heartbeat ticks do not post notification when disabled in settings`() =
        testScope.runTest {
            appSettingsFlow.value = AppSettings(aodSummaryEnabled = false)

            controller.start()
            testScheduler.advanceTimeBy(150L)

            verify(notificationHelper, never()).sendAodSummaryNotification(any(), any(), any())

            testScheduler.advanceTimeBy(Constants.Aod.HEARTBEAT_INTERVAL_MS)

            verify(notificationHelper, never()).sendAodSummaryNotification(any(), any(), any())
            withTimeout(5.seconds) {
                controller.observationJob?.cancelAndJoin()
            }
        }

    private fun createGlobalSnapshot(
        timestamp: Long = 1000L,
        sims: List<SimSnapshot> = emptyList(),
    ): GlobalSnapshot =
        GlobalSnapshot(
            version = SchemaVersion(1, 0, 0),
            timestamp = timestamp,
            simSnapshots = sims,
            capabilities = emptyList(),
        )

    private fun createSimSnapshot(
        slot: Int,
        operatorName: String = "Carrier",
        minutesLimit: Long = 0L,
        minutesRemaining: Long = 0L,
        trafficLimit: Long = 0L,
        trafficRemainingBytes: Long = 0L,
    ): SimSnapshot {
        val entities = mutableMapOf<PackageType, SemanticEntity>()
        if (minutesLimit > 0) {
            entities[PackageType.MINUTES] =
                SemanticEntity(
                    id = "min_$slot",
                    simSlot = slot,
                    operatorName = operatorName,
                    packageType = PackageType.MINUTES,
                    limit = minutesLimit,
                    used = minutesLimit - minutesRemaining,
                    remaining = minutesRemaining,
                    unit = MeasurementUnit.MINUTES,
                    periodStart = 0L,
                    periodEnd = 0L,
                    lastUpdateTime = 0L,
                    sourceType = DataSourceType.SYSTEM,
                )
        }
        if (trafficLimit > 0) {
            entities[PackageType.DATA] =
                SemanticEntity(
                    id = "data_$slot",
                    simSlot = slot,
                    operatorName = operatorName,
                    packageType = PackageType.DATA,
                    limit = trafficLimit,
                    used = trafficLimit - trafficRemainingBytes,
                    remaining = trafficRemainingBytes,
                    unit = MeasurementUnit.BYTES,
                    periodStart = 0L,
                    periodEnd = 0L,
                    lastUpdateTime = 0L,
                    sourceType = DataSourceType.SYSTEM,
                )
        }
        return SimSnapshot(
            slot = slot,
            identity = "id_$slot",
            operatorName = operatorName,
            entities = entities,
            autoResetDay = 1,
        )
    }
}
