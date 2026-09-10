package com.example.minutewatcher.feature.semantic

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.common.util.FixedTimeProvider
import com.example.minutewatcher.core.domain.NavigationProvider
import com.example.minutewatcher.core.model.DataSourceType
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.MeasurementUnit
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.SemanticEntity
import com.example.minutewatcher.core.model.SimSnapshot
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.milliseconds
import android.content.pm.ShortcutManager as AndroidShortcutManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en")
@OptIn(ExperimentalCoroutinesApi::class)
class ShortcutManagerTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var context: Context
    private val navigationProvider: NavigationProvider = mock()
    private val testScope = TestScope(mainDispatcherRule.testDispatcher)
    private val timeProvider = FixedTimeProvider(100000L)
    private val shortcutApplier: ShortcutApplier = mock()
    private lateinit var shortcutManager: ShortcutManager
    private lateinit var androidShortcutManager: AndroidShortcutManager

    @Before
    fun setUp() {
        context =
            androidx.test.core.app.ApplicationProvider
                .getApplicationContext<Context>()
        androidShortcutManager = context.getSystemService(AndroidShortcutManager::class.java)

        whenever(navigationProvider.mainActivityClassName).thenReturn("MainActivity")

        whenever(shortcutApplier.apply(any(), any())).thenAnswer { invocation ->
            val ctx = invocation.getArgument<Context>(0)
            val shortcuts = invocation.getArgument<List<ShortcutInfoCompat>>(1)
            androidx.core.content.pm.ShortcutManagerCompat
                .setDynamicShortcuts(ctx, shortcuts)
            Unit
        }

        shortcutManager =
            ShortcutManager(
                context,
                testScope,
                navigationProvider,
                mainDispatcherRule.testDispatcher,
                shortcutApplier,
            )
    }

    @Test
    fun `shortcuts are eventually updated even with frequent changes`() =
        runTest {
            val flow =
                MutableStateFlow(
                    createSnapshot(
                        listOf(
                            SimSnapshot(
                                0,
                                "sim_0",
                                "MTS",
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
                advanceTimeBy(10_000.milliseconds)
                flow.value =
                    createSnapshot(
                        listOf(
                            SimSnapshot(
                                0,
                                "sim_0",
                                "Op_$i",
                                mapOf(
                                    PackageType.MINUTES to createEntity(0, "Op_$i", PackageType.MINUTES, 100, 10),
                                ),
                                1,
                            ),
                        ),
                    )
            }

            advanceTimeBy(31_000.milliseconds)
            advanceUntilIdle()

            val shortcuts = androidShortcutManager.dynamicShortcuts
            assertTrue("Op_" in shortcuts[0].longLabel.toString())
        }

    @Test
    fun `initialize updates shortcuts immediately with correct labels from resources`() =
        runTest {
            val remainingMinutes = 90L
            val operator = "MTS"
            val entity = createEntity(0, operator, PackageType.MINUTES, 100, 100 - remainingMinutes)
            val snapshot =
                createSnapshot(
                    listOf(
                        SimSnapshot(
                            0,
                            "sim_0",
                            operator,
                            mapOf(
                                PackageType.MINUTES to entity,
                            ),
                            1,
                        ),
                    ),
                )
            val flow = MutableStateFlow(snapshot)

            shortcutManager.initialize(flow)
            advanceTimeBy(31_000.milliseconds)
            advanceUntilIdle()

            val shortcuts = androidShortcutManager.dynamicShortcuts
            assertEquals(1, shortcuts.size)

            val shortcut = shortcuts[0]
            // Label is now from shortcut_minutes_short resource: "%1$d %2$s" -> "90 min."
            assertEquals("90 min.", shortcut.shortLabel.toString())
            // Long label is now from shortcut_long_format resource: "%1$s: %2$s %3$s"
            // "MTS: Remaining: 90 min." (en locale from @Config)
            assertEquals("MTS: Remaining: 90 min.", shortcut.longLabel.toString())
        }

    @Test
    fun `shortcuts are limited by platform max count`() =
        runTest {
            // Robolectric default max is 15 or 10. Let's force many SIMs.
            val sims = (0 until 10).map { i ->
                SimSnapshot(
                    i,
                    "sim_$i",
                    "Op_$i",
                    mapOf(
                        PackageType.MINUTES to createEntity(i, "Op_$i", PackageType.MINUTES, 100, 10),
                        PackageType.DATA to createEntity(i, "Op_$i", PackageType.DATA, 1000, 100)
                    ),
                    1
                )
            }
            val flow = MutableStateFlow(createSnapshot(sims))

            shortcutManager.initialize(flow)
            advanceTimeBy(31_000.milliseconds)
            advanceUntilIdle()

            val shortcuts = androidShortcutManager.dynamicShortcuts
            val maxAllowed = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)

            assertTrue("Should not exceed max allowed: $maxAllowed, was ${shortcuts.size}", shortcuts.size <= maxAllowed)
            assertEquals(maxAllowed, shortcuts.size)
        }

    @Test
    fun `no shortcut is created when limit is 0`() =
        runTest {
            val entity = createEntity(0, "MTS", PackageType.MINUTES, 0, 0)
            val snapshot =
                createSnapshot(
                    listOf(
                        SimSnapshot(
                            0,
                            "sim_0",
                            "MTS",
                            mapOf(
                                PackageType.MINUTES to entity,
                            ),
                            1,
                        ),
                    ),
                )
            val flow = MutableStateFlow(snapshot)

            shortcutManager.initialize(flow)
            advanceTimeBy(31_000.milliseconds)
            advanceUntilIdle()

            assertEquals(0, androidShortcutManager.dynamicShortcuts.size)
        }

    @Test
    fun `concurrent initialize and recover does not crash and maintains consistency`() =
        runTest {
            val flow = MutableStateFlow(createSnapshot(emptyList()))

            // Start multiple concurrent operations
            val jobs =
                List(10) { i ->
                    launch {
                        if (i % 2 == 0) {
                            shortcutManager.initialize(flow)
                        } else {
                            shortcutManager.recover()
                        }
                    }
                }
            jobs.joinAll()
            advanceUntilIdle()

            assertTrue(shortcutManager.status.value is TrackingStatus.Active)
        }

    @Test
    fun `unsupported operation sets NOT_SUPPORTED status`() =
        runTest {
            whenever(
                shortcutApplier.apply(
                    any(),
                    any(),
                ),
            ).thenThrow(UnsupportedOperationException("Shortcuts not supported"))

            val result = shortcutManager.updateShortcutsSync(createSnapshot(emptyList()))
            advanceUntilIdle()

            assertFalse(result)
            val status = shortcutManager.status.value
            assertTrue(status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorType.NOT_SUPPORTED, (status as TrackingStatus.Error).type)
        }

    @Test
    fun `updateShortcutsSync ignores empty snapshot and does not remove shortcuts`() =
        runTest {
            // 1. Initial shortcuts setup
            val snapshot =
                createSnapshot(
                    listOf(
                        SimSnapshot(
                            0,
                            "sim_0",
                            "MTS",
                            mapOf(
                                PackageType.MINUTES to createEntity(0, "MTS", PackageType.MINUTES, 100, 10),
                            ),
                            1,
                        ),
                    ),
                )
            shortcutManager.updateShortcutsSync(snapshot)
            assertTrue(androidShortcutManager.dynamicShortcuts.isNotEmpty())

            // 2. Pass empty snapshot (timestamp = 0)
            val emptySnapshot = snapshot.copy(timestamp = 0L)
            val result = shortcutManager.updateShortcutsSync(emptySnapshot)

            // 3. Verify shortcuts NOT removed
            assertTrue(result)
            assertTrue("Shortcuts should still exist", androidShortcutManager.dynamicShortcuts.isNotEmpty())
        }

    @Test
    fun `updateShortcutsSync removes shortcuts for loaded snapshot without SIMs`() =
        runTest {
            // 1. Initial shortcuts setup
            val snapshot =
                createSnapshot(
                    listOf(
                        SimSnapshot(
                            0,
                            "sim_0",
                            "MTS",
                            mapOf(
                                PackageType.MINUTES to createEntity(0, "MTS", PackageType.MINUTES, 100, 10),
                            ),
                            1,
                        ),
                    ),
                )
            shortcutManager.updateShortcutsSync(snapshot)
            assertTrue(androidShortcutManager.dynamicShortcuts.isNotEmpty())

            // 2. Pass loaded snapshot with no SIMs (timestamp != 0)
            val noSimSnapshot = snapshot.copy(timestamp = 1000L, simSnapshots = emptyList())
            val result = shortcutManager.updateShortcutsSync(noSimSnapshot)

            // 3. Verify shortcuts ARE removed
            assertTrue(result)
            assertTrue("Shortcuts should be removed", androidShortcutManager.dynamicShortcuts.isEmpty())
        }

    @Suppress("SameParameterValue")
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
