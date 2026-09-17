package com.example.minutewatcher.feature.semantic

import android.content.Context
import com.example.minutewatcher.core.domain.NavigationProvider
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SchemaVersion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

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
    fun `ShortcutManager setupObservation does NOT proceed if cancelled during join`() =
        runTest {
            val oldJob = setupOldJobInShortcutManager()
            val method = getSetupObservationMethod()

            val snapshotFlow = createSnapshotFlow()
            var setupObservationFinished = false
            val job =
                launch {
                    try {
                        suspendCoroutineUninterceptedOrReturn<Any?> { cont ->
                            method.invoke(
                                shortcutManager,
                                snapshotFlow,
                                cont,
                            )
                        }
                        setupObservationFinished = true
                    } catch (
                        @Suppress("SwallowedException")
                        e: Exception,
                    ) {
                        // expected
                    }
                }

            runCurrent()
            job.cancel()
            runCurrent()

            testScheduler.advanceTimeBy(2000)
            runCurrent()

            assertFalse(
                "setupObservation should have been cancelled and NOT finished",
                setupObservationFinished,
            )

            val field =
                ShortcutManager::class.java.getDeclaredField("job").apply {
                    isAccessible = true
                }
            val currentJob = field.get(shortcutManager) as? Job
            assertFalse(
                "New job should NOT have been launched",
                currentJob != null && currentJob != oldJob && currentJob.isActive,
            )
        }

    private fun setupOldJobInShortcutManager(): Job {
        val field =
            ShortcutManager::class.java.getDeclaredField("job").apply {
                isAccessible = true
            }

        // Create a job that will stay in 'Cancelling' state for a bit
        val oldJob =
            testScope.launch {
                withContext(NonCancellable) {
                    delay(1000)
                }
            }
        field.set(shortcutManager, oldJob)
        return oldJob
    }

    private fun getSetupObservationMethod() =
        ShortcutManager::class.java
            .getDeclaredMethod(
                "setupObservation",
                StateFlow::class.java,
                Continuation::class.java,
            ).apply {
                isAccessible = true
            }

    private fun createSnapshotFlow() =
        MutableStateFlow(
            GlobalSnapshot(
                version = SchemaVersion(1, 0, 0),
                timestamp = 1000L,
                simSnapshots = emptyList(),
                capabilities = emptyList(),
            ),
        )
}
