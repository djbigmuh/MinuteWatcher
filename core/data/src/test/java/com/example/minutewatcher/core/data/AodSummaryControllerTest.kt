package com.example.minutewatcher.core.data

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ApplicationProvider
import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.PermissionRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.DataSourceType
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.MeasurementUnit
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.SemanticEntity
import com.example.minutewatcher.core.model.SimSnapshot
import com.example.minutewatcher.core.resources.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
        runTest {
            appSettingsFlow.value = AppSettings(aodSummaryEnabled = false)
            controller.start()
            testScope.advanceUntilIdle()

            verify(notificationHelper, never()).sendAodSummaryNotification(any(), any(), any())
        }

    @Test
    fun `setting enabled with valid SIMs posts notification sorted by slot`() =
        runTest {
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
            // Emitted out of order
            snapshotFlow.value = createGlobalSnapshot(timestamp = 1000L, sims = listOf(sim1, sim0))

            controller.start()
            testScope.advanceUntilIdle()

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

            // Slot 0 line comes first
            val line0 = expectedLines[0]
            val line1 = expectedLines[1]

            assertEquals(
                "MTS • 50 minutes",
                line0,
            )
            assertEquals(
                "Beeline • 120 minutes • 5.0 GB",
                line1,
            )
        }

    @Test
    fun `SIM without limits is excluded and empty lines cancels summary`() =
        runTest {
            appSettingsFlow.value = AppSettings(aodSummaryEnabled = true)
            val simNoLimits = createSimSnapshot(0, "MTS")
            snapshotFlow.value = createGlobalSnapshot(timestamp = 1000L, sims = listOf(simNoLimits))

            controller.start()
            testScope.advanceUntilIdle()

            verify(notificationHelper, never()).sendAodSummaryNotification(any(), any(), any())
        }

    @Test
    fun `empty timestamp snapshot cancels summary if posted`() =
        runTest {
            appSettingsFlow.value = AppSettings(aodSummaryEnabled = true)
            val sim0 = createSimSnapshot(0, "MTS", minutesLimit = 100, minutesRemaining = 50)

            // First post
            snapshotFlow.value = createGlobalSnapshot(timestamp = 1000L, sims = listOf(sim0))
            controller.start()
            testScope.advanceUntilIdle()
            verify(notificationHelper, times(1)).sendAodSummaryNotification(any(), any(), any())

            // Then empty timestamp
            snapshotFlow.value = createGlobalSnapshot(timestamp = Constants.Data.EMPTY_TIMESTAMP, sims = listOf(sim0))
            testScope.advanceUntilIdle()

            verify(notificationHelper, times(1)).cancelAodSummaryNotification()
        }

    @Test
    fun `permission revoked cancels posted summary`() =
        runTest {
            appSettingsFlow.value = AppSettings(aodSummaryEnabled = true)
            val sim0 = createSimSnapshot(0, "MTS", minutesLimit = 100, minutesRemaining = 50)

            snapshotFlow.value = createGlobalSnapshot(timestamp = 1000L, sims = listOf(sim0))
            controller.start()
            testScope.advanceUntilIdle()

            verify(notificationHelper, times(1)).sendAodSummaryNotification(any(), any(), any())

            permissionFlow.value = false
            testScope.advanceUntilIdle()

            verify(notificationHelper, times(1)).cancelAodSummaryNotification()
        }

    @Test
    fun `channel blocked cancels posted summary`() =
        runTest {
            appSettingsFlow.value = AppSettings(aodSummaryEnabled = true)
            val sim0 = createSimSnapshot(0, "MTS", minutesLimit = 100, minutesRemaining = 50)

            snapshotFlow.value = createGlobalSnapshot(timestamp = 1000L, sims = listOf(sim0))
            controller.start()
            testScope.advanceUntilIdle()

            verify(notificationHelper, times(1)).sendAodSummaryNotification(any(), any(), any())

            whenever(notificationHelper.isAodSummaryAvailable()).thenReturn(false)
            snapshotFlow.value = createGlobalSnapshot(timestamp = 2000L, sims = listOf(sim0))
            testScope.advanceUntilIdle()

            verify(notificationHelper, times(1)).cancelAodSummaryNotification()
        }

    @Test
    fun `deduplication prevents duplicate send for identical content`() =
        runTest {
            appSettingsFlow.value = AppSettings(aodSummaryEnabled = true)
            val sim0 = createSimSnapshot(0, "MTS", minutesLimit = 100, minutesRemaining = 50)

            snapshotFlow.value = createGlobalSnapshot(timestamp = 1000L, sims = listOf(sim0))
            controller.start()
            testScope.advanceUntilIdle()

            snapshotFlow.value = createGlobalSnapshot(timestamp = 2000L, sims = listOf(sim0))
            testScope.advanceUntilIdle()

            verify(notificationHelper, times(1)).sendAodSummaryNotification(any(), any(), any())
        }

    @Test
    fun `content change triggers new send`() =
        runTest {
            appSettingsFlow.value = AppSettings(aodSummaryEnabled = true)
            val sim0 = createSimSnapshot(0, "MTS", minutesLimit = 100, minutesRemaining = 50)

            snapshotFlow.value = createGlobalSnapshot(timestamp = 1000L, sims = listOf(sim0))
            controller.start()
            testScope.advanceUntilIdle()

            val sim0Updated = createSimSnapshot(0, "MTS", minutesLimit = 100, minutesRemaining = 40)
            snapshotFlow.value = createGlobalSnapshot(timestamp = 2000L, sims = listOf(sim0Updated))
            testScope.advanceUntilIdle()

            verify(notificationHelper, times(2)).sendAodSummaryNotification(any(), any(), any())
        }

    @Test
    fun `disabling setting cancels summary and re-enabling resends`() =
        runTest {
            appSettingsFlow.value = AppSettings(aodSummaryEnabled = true)
            val sim0 = createSimSnapshot(0, "MTS", minutesLimit = 100, minutesRemaining = 50)

            snapshotFlow.value = createGlobalSnapshot(timestamp = 1000L, sims = listOf(sim0))
            controller.start()
            testScope.advanceUntilIdle()

            appSettingsFlow.value = AppSettings(aodSummaryEnabled = false)
            testScope.advanceUntilIdle()

            verify(notificationHelper, times(1)).cancelAodSummaryNotification()

            appSettingsFlow.value = AppSettings(aodSummaryEnabled = true)
            testScope.advanceUntilIdle()

            verify(notificationHelper, times(2)).sendAodSummaryNotification(any(), any(), any())
        }

    @Test
    fun `calling start multiple times does not duplicate observers`() =
        runTest {
            appSettingsFlow.value = AppSettings(aodSummaryEnabled = true)
            val sim0 = createSimSnapshot(0, "MTS", minutesLimit = 100, minutesRemaining = 50)

            snapshotFlow.value = createGlobalSnapshot(timestamp = 1000L, sims = listOf(sim0))
            controller.start()
            controller.start()
            testScope.advanceUntilIdle()

            verify(notificationHelper, times(1)).sendAodSummaryNotification(any(), any(), any())
        }

    @Test
    fun `exception during send does not kill observer and subsequent update retries`() =
        runTest {
            appSettingsFlow.value = AppSettings(aodSummaryEnabled = true)
            val sim0 = createSimSnapshot(0, "MTS", minutesLimit = 100, minutesRemaining = 50)

            snapshotFlow.value = createGlobalSnapshot(timestamp = 1000L, sims = listOf(sim0))
            whenever(
                notificationHelper.sendAodSummaryNotification(any(), any(), any()),
            ).thenThrow(RuntimeException("Transient error"))

            controller.start()
            testScope.advanceUntilIdle()

            whenever(
                notificationHelper.sendAodSummaryNotification(any(), any(), any()),
            ).thenReturn(true)

            val sim0Updated = createSimSnapshot(0, "MTS", minutesLimit = 100, minutesRemaining = 40)
            snapshotFlow.value = createGlobalSnapshot(timestamp = 2000L, sims = listOf(sim0Updated))
            testScope.advanceUntilIdle()

            verify(notificationHelper, times(2)).sendAodSummaryNotification(any(), any(), any())
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

    @Test
    fun `summary lines format strings according to app locale`() =
        runTest {
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
                testScope.advanceUntilIdle()

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
            }
        }
}
