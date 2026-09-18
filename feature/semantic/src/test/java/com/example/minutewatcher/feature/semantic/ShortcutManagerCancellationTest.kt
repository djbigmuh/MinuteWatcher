package com.example.minutewatcher.feature.semantic

import android.content.Context
import com.example.minutewatcher.core.domain.NavigationProvider
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SchemaVersion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

@OptIn(ExperimentalCoroutinesApi::class)
class ShortcutManagerCancellationTest {
    private val context: Context = mock()
    private val navigationProvider: NavigationProvider = mock()
    private val shortcutApplier: ShortcutApplier = mock()
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var shortcutManager: ShortcutManager

    @Before
    fun setup() {
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
            val flow1 = MutableStateFlow(createSnapshot(100L))
            val flow2 = MutableStateFlow(createSnapshot(200L))

            // 1. Initial observation
            shortcutManager.initialize(flow1)
            runCurrent()
            advanceUntilIdle() // Process first emission
            verify(shortcutApplier, times(1)).apply(any(), any())

            // 2. Re-initialize with new flow
            shortcutManager.initialize(flow2)
            runCurrent()
            advanceUntilIdle() // Process first emission of flow2
            verify(shortcutApplier, times(2)).apply(any(), any())

            // 3. Emit in old flow - should NOT trigger anything
            flow1.value = createSnapshot(101L)
            advanceUntilIdle()
            verify(shortcutApplier, times(2)).apply(any(), any())

            // 4. Emit in new flow - should trigger
            flow2.value = createSnapshot(201L)
            advanceUntilIdle()
            verify(shortcutApplier, times(3)).apply(any(), any())
        }

    @Test
    fun `recover restores observation of last flow`() =
        runTest(testDispatcher) {
            val flow = MutableStateFlow(createSnapshot(100L))
            shortcutManager.initialize(flow)
            advanceUntilIdle()
            verify(shortcutApplier, times(1)).apply(any(), any())

            // Simulate recovery (e.g. after process death or manual trigger)
            shortcutManager.recover()
            advanceUntilIdle()

            // Emission should still work
            flow.value = createSnapshot(101L)
            advanceUntilIdle()
            verify(shortcutApplier, atLeastOnce()).apply(any(), any())
        }

    private fun createSnapshot(timestamp: Long) =
        GlobalSnapshot(
            version = SchemaVersion(1, 0, 0),
            timestamp = timestamp,
            simSnapshots = emptyList(),
            capabilities = emptyList(),
            isManualUpdate = true, // 0 debounce
        )
}
