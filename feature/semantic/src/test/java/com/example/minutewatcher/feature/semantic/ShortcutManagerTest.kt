package com.example.minutewatcher.feature.semantic

import android.content.Context
import com.example.minutewatcher.core.common.util.FixedTimeProvider
import com.example.minutewatcher.core.domain.NavigationProvider
import com.example.minutewatcher.core.model.DataSourceType
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.MeasurementUnit
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.SemanticEntity
import com.example.minutewatcher.core.model.SimSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.content.pm.ShortcutManager as AndroidShortcutManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en")
@OptIn(ExperimentalCoroutinesApi::class)
class ShortcutManagerTest {
    private lateinit var context: Context
    private val navigationProvider: NavigationProvider = mock()
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val timeProvider = FixedTimeProvider(100000L)
    private lateinit var shortcutManager: ShortcutManager
    private lateinit var androidShortcutManager: AndroidShortcutManager

    @Before
    fun setUp() {
        context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        androidShortcutManager = context.getSystemService(AndroidShortcutManager::class.java)

        whenever(navigationProvider.mainActivityClassName).thenReturn("MainActivity")

        shortcutManager =
            ShortcutManager(
                context,
                testScope,
                navigationProvider,
                testDispatcher,
            )
    }

    @Test
    fun `shortcuts are eventually updated even with frequent changes`() =
        runTest(testDispatcher) {
            val flow =
                MutableStateFlow(
                    createSnapshot(
                        listOf(
                            SimSnapshot(
                                0,
                                "sim_0",
                                "MTS",
                                true,
                                mapOf(
                                    PackageType.MINUTES to createEntity(0, "MTS", PackageType.MINUTES, 100, 10),
                                ),
                                1,
                            ),
                        ),
                    ),
                )

            shortcutManager.initialize(flow)

            // Emit changes every 10 seconds.
            repeat(10) { i ->
                timeProvider.advance(10_000)
                testDispatcher.scheduler.advanceTimeBy(10_000)
                flow.value =
                    createSnapshot(
                        listOf(
                            SimSnapshot(
                                0,
                                "sim_0",
                                "Op_$i",
                                true,
                                mapOf(
                                    PackageType.MINUTES to createEntity(0, "Op_$i", PackageType.MINUTES, 100, 10),
                                ),
                                1,
                            ),
                        ),
                    )
            }

            testDispatcher.scheduler.advanceTimeBy(31_000)

            val shortcuts = androidShortcutManager.dynamicShortcuts
            assertTrue("Op_" in shortcuts[0].longLabel.toString())
        }

    @Test
    fun `initialize updates shortcuts immediately with correct labels`() =
        runTest(testDispatcher) {
            val remainingMinutes = 90L
            val operator = "MTS"
            val entity = createEntity(0, operator, PackageType.MINUTES, 100, 100 - remainingMinutes)
            val snapshot = createSnapshot(listOf(SimSnapshot(0, "sim_0", operator, true, mapOf(PackageType.MINUTES to entity), 1)))
            val flow = MutableStateFlow(snapshot)

            shortcutManager.initialize(flow)
            testDispatcher.scheduler.advanceTimeBy(31_000)

            val shortcuts = androidShortcutManager.dynamicShortcuts
            assertEquals(1, shortcuts.size)

            val shortcut = shortcuts[0]
            assertEquals("90 min.", shortcut.shortLabel.toString())
            // In English it should be "MTS: Remaining: 90 min."
            assertEquals("MTS: Remaining: 90 min.", shortcut.longLabel.toString())
        }

    @Test
    fun `no shortcut is created when limit is 0`() =
        runTest(testDispatcher) {
            val entity = createEntity(0, "MTS", PackageType.MINUTES, 0, 0)
            val snapshot = createSnapshot(listOf(SimSnapshot(0, "sim_0", "MTS", true, mapOf(PackageType.MINUTES to entity), 1)))
            val flow = MutableStateFlow(snapshot)

            shortcutManager.initialize(flow)
            testDispatcher.scheduler.advanceTimeBy(31_000)

            assertEquals(0, androidShortcutManager.dynamicShortcuts.size)
        }

    private fun createEntity(
        slot: Int,
        op: String,
        packageType: PackageType,
        limit: Long,
        used: Long,
    ) = SemanticEntity(
        id = "id_${slot}_$packageType",
        simSlot = slot,
        operatorName = op,
        packageType = packageType,
        limit = limit,
        used = used,
        remaining = limit - used,
        unit = if (packageType == PackageType.MINUTES) MeasurementUnit.MINUTES else MeasurementUnit.BYTES,
        periodStart = 0,
        periodEnd = 0,
        lastUpdateTime = 1000L,
        sourceType = DataSourceType.SYSTEM,
    )

    private fun createSnapshot(sims: List<SimSnapshot>) =
        GlobalSnapshot(
            version = SchemaVersion(1, 0, 0),
            timestamp = 1000L,
            simSnapshots = sims,
            capabilities = emptyList(),
        )
}
