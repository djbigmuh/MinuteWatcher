package host.serv.djbigmuh.minutewatcher.feature.semantic

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import host.serv.djbigmuh.minutewatcher.core.domain.NavigationProvider
import host.serv.djbigmuh.minutewatcher.core.model.DataSourceType
import host.serv.djbigmuh.minutewatcher.core.model.GlobalSnapshot
import host.serv.djbigmuh.minutewatcher.core.model.MeasurementUnit
import host.serv.djbigmuh.minutewatcher.core.model.PackageType
import host.serv.djbigmuh.minutewatcher.core.model.SchemaVersion
import host.serv.djbigmuh.minutewatcher.core.model.SemanticEntity
import host.serv.djbigmuh.minutewatcher.core.model.SimSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en")
@OptIn(ExperimentalCoroutinesApi::class)
class ShortcutManagerCancellationTest {
    private lateinit var context: Context
    private val navigationProvider: NavigationProvider = mock()
    private val shortcutApplier: ShortcutApplier = mock()
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var shortcutManager: ShortcutManager

    @Before
    fun setup() {
        context =
            androidx.test.core.app.ApplicationProvider
                .getApplicationContext<Context>()
        whenever(navigationProvider.mainActivityClassName).thenReturn("MainActivity")
        whenever(shortcutApplier.apply(any(), any())).thenAnswer { invocation ->
            val ctx = invocation.getArgument<Context>(0)
            val shortcuts = invocation.getArgument<List<ShortcutInfoCompat>>(1)
            ShortcutManagerCompat.setDynamicShortcuts(ctx, shortcuts)
            Unit
        }

        shortcutManager =
            ShortcutManager(
                context,
                testScope,
                navigationProvider,
                testDispatcher,
                shortcutApplier,
            )
    }

    @Test
    fun `repeated initialize replaces previous observation and only new one reacts`() =
        runTest(testDispatcher) {
            val flow1 = MutableStateFlow(createSnapshot(100L, "MTS"))
            val flow2 = MutableStateFlow(createSnapshot(200L, "Beeline"))

            // 1. Initial observation
            shortcutManager.initialize(flow1)
            runCurrent()
            advanceUntilIdle() // Process first emission
            verify(shortcutApplier, times(1)).apply(any(), any())

            // 2. Re-initialize with new flow (with different operator to produce different projection)
            shortcutManager.initialize(flow2)
            runCurrent()
            advanceUntilIdle() // Process first emission of flow2
            verify(shortcutApplier, times(2)).apply(any(), any())

            // 3. Emit in old flow - should NOT trigger anything
            flow1.value = createSnapshot(101L, "Megafon")
            advanceUntilIdle()
            verify(shortcutApplier, times(2)).apply(any(), any())

            // 4. Emit in new flow - should trigger
            flow2.value = createSnapshot(201L, "Tele2")
            advanceUntilIdle()
            verify(shortcutApplier, times(3)).apply(any(), any())
        }

    @Test
    fun `recover restores observation of last flow`() =
        runTest(testDispatcher) {
            val flow = MutableStateFlow(createSnapshot(100L, "MTS"))
            shortcutManager.initialize(flow)
            advanceUntilIdle()
            verify(shortcutApplier, times(1)).apply(any(), any())

            // Simulate recovery (e.g. after process death or manual trigger)
            shortcutManager.recover()
            advanceUntilIdle()

            // Emission should still work
            flow.value = createSnapshot(101L, "Beeline")
            advanceUntilIdle()
            verify(shortcutApplier, atLeastOnce()).apply(any(), any())
        }

    private fun createSnapshot(
        timestamp: Long,
        operator: String,
    ) = GlobalSnapshot(
        version = SchemaVersion(1, 0, 0),
        timestamp = timestamp,
        simSnapshots =
            listOf(
                SimSnapshot(
                    slot = 0,
                    identity = "sim_0",
                    operatorName = operator,
                    entities =
                        mapOf(
                            PackageType.MINUTES to
                                SemanticEntity(
                                    id = "id_0_MINUTES",
                                    simSlot = 0,
                                    operatorName = operator,
                                    packageType = PackageType.MINUTES,
                                    limit = 100L,
                                    used = 10L,
                                    remaining = 90L,
                                    unit = MeasurementUnit.MINUTES,
                                    periodStart = 0L,
                                    periodEnd = 0L,
                                    lastUpdateTime = timestamp,
                                    sourceType = DataSourceType.SYSTEM,
                                ),
                        ),
                    autoResetDay = 1,
                ),
            ),
        capabilities = emptyList(),
        isManualUpdate = true, // 0 debounce
    )
}
