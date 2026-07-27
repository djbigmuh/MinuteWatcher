package com.example.minutewatcher.feature.semantic

import android.content.Context
import android.content.pm.ShortcutManager as AndroidShortcutManager
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class ShortcutManagerTest {
    private val context: Context = mock()
    private val navigationProvider: NavigationProvider = mock()
    private val testScope = TestScope()
    private lateinit var shortcutManager: ShortcutManager
    private lateinit var androidShortcutManager: AndroidShortcutManager

    @Before
    fun setUp() {
        val realContext = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        androidShortcutManager = realContext.getSystemService(AndroidShortcutManager::class.java)
        
        whenever(context.getSystemService(AndroidShortcutManager::class.java)).thenReturn(androidShortcutManager)
        whenever(context.packageName).thenReturn("com.example.minutewatcher")
        whenever(context.getString(any())).thenReturn("text")
        whenever(context.getString(any(), any())).thenReturn("text")
        whenever(navigationProvider.mainActivityClassName).thenReturn("MainActivity")
        
        shortcutManager = ShortcutManager(context, testScope, navigationProvider)
    }

    @Test
    fun `initialize updates shortcuts immediately`() = runTest {
        val entity = createEntity(0, "MTS", PackageType.MINUTES, 100, 10)
        val snapshot = createSnapshot(listOf(SimSnapshot(0, "MTS", true, mapOf(PackageType.MINUTES to entity))))
        val flow = MutableStateFlow(snapshot)

        shortcutManager.initialize(flow)
        testScope.advanceUntilIdle()

        val shortcuts = androidShortcutManager.dynamicShortcuts
        assertEquals(1, shortcuts.size)
        assertEquals("minutes_shortcut_0", shortcuts[0].id)
    }

    @Test
    fun `updateShortcuts removes shortcuts when no active SIMs have limits`() = runTest {
        // 1. First add a shortcut
        val entity = createEntity(0, "MTS", PackageType.MINUTES, 100, 10)
        val snapshot1 = createSnapshot(listOf(SimSnapshot(0, "MTS", true, mapOf(PackageType.MINUTES to entity))))
        val flow = MutableStateFlow(snapshot1)
        
        shortcutManager.initialize(flow)
        testScope.advanceUntilIdle()
        assertEquals(1, androidShortcutManager.dynamicShortcuts.size)

        // 2. Update with no limits
        val snapshot2 = createSnapshot(listOf(SimSnapshot(0, "MTS", true, emptyMap())))
        flow.value = snapshot2
        
        // Advance time for debounce (30s)
        testScope.testScheduler.advanceTimeBy(31_000)
        testScope.advanceUntilIdle() 
        
        assertEquals(0, androidShortcutManager.dynamicShortcuts.size)
    }

    @Test
    fun `shortcuts are only created for supported slots`() = runTest {
        // Slot 0 (Supported)
        val e0 = createEntity(0, "MTS", PackageType.MINUTES, 100, 10)
        // Slot 2 (Unsupported)
        val e2 = createEntity(2, "Tele2", PackageType.MINUTES, 200, 20)
        
        val snapshot = createSnapshot(listOf(
            SimSnapshot(0, "MTS", true, mapOf(PackageType.MINUTES to e0)),
            SimSnapshot(2, "Tele2", true, mapOf(PackageType.MINUTES to e2))
        ))
        
        val flow = MutableStateFlow(snapshot)
        shortcutManager.initialize(flow)
        testScope.advanceUntilIdle()

        val shortcuts = androidShortcutManager.dynamicShortcuts
        assertEquals(1, shortcuts.size)
        assertEquals("minutes_shortcut_0", shortcuts[0].id)
    }

    private fun createEntity(slot: Int, op: String, type: PackageType, limit: Long, used: Long) = SemanticEntity(
        id = "id_$slot",
        simSlot = slot,
        operatorName = op,
        packageType = type,
        limit = limit,
        used = used,
        remaining = limit - used,
        unit = if (type == PackageType.MINUTES) MeasurementUnit.MINUTES else MeasurementUnit.BYTES,
        periodStart = 0,
        periodEnd = 0,
        lastUpdateTime = System.currentTimeMillis(),
        sourceType = DataSourceType.SYSTEM
    )

    private fun createSnapshot(sims: List<SimSnapshot>) = GlobalSnapshot(
        version = SchemaVersion(1, 0, 0),
        timestamp = System.currentTimeMillis(),
        simSnapshots = sims,
        capabilities = emptyList()
    )
}
