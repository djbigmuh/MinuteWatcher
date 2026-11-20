package com.github.djbigmuh.minutewatcher.feature.main.controller

import androidx.lifecycle.SavedStateHandle
import com.github.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import com.github.djbigmuh.minutewatcher.core.common.util.TimeProvider
import com.github.djbigmuh.minutewatcher.feature.main.MainViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class MainResumeControllerTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val timeProvider: TimeProvider = mock()
    private val savedStateHandle: SavedStateHandle = SavedStateHandle()
    private val initController: MainInitializationController = mock()
    private val initializationState = MutableStateFlow<InitializationState>(InitializationState.NotStarted)

    private lateinit var controller: MainResumeController

    @Before
    fun setup() {
        whenever(initController.initializationState).thenReturn(initializationState)
        controller = MainResumeController(timeProvider, savedStateHandle)
    }

    @Test
    fun `onActivityResume does not refresh if initialization completed during wait and time is fresh`() =
        runTest {
            val nowAtResume = 100000L
            whenever(timeProvider.elapsedRealtime()).thenReturn(nowAtResume)
            initializationState.value = InitializationState.InProgress

            var refreshCalled = false
            controller.onActivityResume(
                scope = backgroundScope,
                initController = initController,
                onRefreshNeeded = {
                    refreshCalled = true
                    true
                },
                onUpdateLastRefreshAt = {},
            )

            runCurrent()

            // 1. Simulate initialization finishing and recording a fresh timestamp
            val freshTime = nowAtResume + 1000L
            savedStateHandle[MainViewModel.KEY_LAST_REFRESH_TIME] = freshTime

            // Current time check will also see the fresh time or later
            whenever(timeProvider.elapsedRealtime()).thenReturn(freshTime + 10L)
            initializationState.value = InitializationState.Completed

            // 2. Wait for status change processing
            runCurrent()

            assertFalse("Should not refresh if timestamp is fresh after init", refreshCalled)
        }

    @Test
    fun `onActivityResume refreshes if time is stale after initialization wait`() =
        runTest {
            val nowAtResume = 100000L
            whenever(timeProvider.elapsedRealtime()).thenReturn(nowAtResume)
            initializationState.value = InitializationState.InProgress

            // 1 minute threshold
            val staleTime = nowAtResume - 70000L
            savedStateHandle[MainViewModel.KEY_LAST_REFRESH_TIME] = staleTime

            var refreshCalled = false
            controller.onActivityResume(
                scope = backgroundScope,
                initController = initController,
                onRefreshNeeded = {
                    refreshCalled = true
                    true
                },
                onUpdateLastRefreshAt = {},
            )

            runCurrent()

            // 2. Initialization completes but timestamp is still stale
            initializationState.value = InitializationState.Completed

            // Current time for staleness check
            whenever(timeProvider.elapsedRealtime()).thenReturn(nowAtResume + 500L)

            runCurrent()

            assertTrue("Should refresh if timestamp is stale after init", refreshCalled)
        }

    @Test
    fun `onActivityResume does not refresh if initialization fails after wait`() =
        runTest {
            whenever(timeProvider.elapsedRealtime()).thenReturn(100000L)
            initializationState.value = InitializationState.InProgress

            var refreshCalled = false
            controller.onActivityResume(
                scope = backgroundScope,
                initController = initController,
                onRefreshNeeded = {
                    refreshCalled = true
                    true
                },
                onUpdateLastRefreshAt = {},
            )

            runCurrent()

            // Initialization fails
            initializationState.value = InitializationState.Failed

            runCurrent()

            assertFalse("Should not refresh if init failed", refreshCalled)
        }

    @Test
    fun `onActivityResume debounce prevents rapid re-runs`() =
        runTest {
            whenever(timeProvider.elapsedRealtime()).thenReturn(100000L)
            initializationState.value = InitializationState.Completed
            savedStateHandle[MainViewModel.KEY_LAST_REFRESH_TIME] = 0L // Force stale

            var refreshCount = 0
            val block =
                suspend {
                    refreshCount++
                    true
                }

            controller.onActivityResume(
                scope = backgroundScope,
                initController = initController,
                onRefreshNeeded = block,
                onUpdateLastRefreshAt = {},
            )
            runCurrent()
            assertEquals(1, refreshCount)

            // Second call immediately
            controller.onActivityResume(
                scope = backgroundScope,
                initController = initController,
                onRefreshNeeded = block,
                onUpdateLastRefreshAt = {},
            )
            runCurrent()
            assertEquals("Should not run again due to debounce", 1, refreshCount)

            // After 6 seconds (threshold 5s)
            whenever(timeProvider.elapsedRealtime()).thenReturn(106000L)
            controller.onActivityResume(
                scope = backgroundScope,
                initController = initController,
                onRefreshNeeded = block,
                onUpdateLastRefreshAt = {},
            )
            runCurrent()
            assertEquals("Should run again after debounce time", 2, refreshCount)
        }

    @Test
    fun `onActivityResume allows execution when clocks reset after reboot`() =
        runTest {
            val now = 1000L
            val lastFuture = now + 100000L // Immitate value from previous session before reboot
            savedStateHandle["last_resume_timestamp"] = lastFuture
            whenever(timeProvider.elapsedRealtime()).thenReturn(now)
            initializationState.value = InitializationState.Completed
            savedStateHandle[MainViewModel.KEY_LAST_REFRESH_TIME] = 0L // Force stale

            var refreshCalled = false
            controller.onActivityResume(
                scope = backgroundScope,
                initController = initController,
                onRefreshNeeded = {
                    refreshCalled = true
                    true
                },
                onUpdateLastRefreshAt = {},
            )
            runCurrent()

            assertTrue("Should run even if time diff is negative (reboot)", refreshCalled)
            assertEquals(
                "Should update last resume timestamp to current now",
                now,
                savedStateHandle.get<Long>("last_resume_timestamp"),
            )
        }

    @Test
    fun `onActivityResume blocks execution within debounce`() =
        runTest {
            val now = 100000L
            val last = now - 1000L // 1 second ago (within 5s debounce)
            savedStateHandle["last_resume_timestamp"] = last
            whenever(timeProvider.elapsedRealtime()).thenReturn(now)
            initializationState.value = InitializationState.Completed
            savedStateHandle[MainViewModel.KEY_LAST_REFRESH_TIME] = 0L

            var refreshCalled = false
            controller.onActivityResume(
                scope = backgroundScope,
                initController = initController,
                onRefreshNeeded = {
                    refreshCalled = true
                    true
                },
                onUpdateLastRefreshAt = {},
            )
            runCurrent()

            assertFalse("Should be blocked by debounce", refreshCalled)
            assertEquals(
                "Should not update timestamp if debounced",
                last,
                savedStateHandle.get<Long>("last_resume_timestamp"),
            )
        }

    @Test
    fun `onActivityResume allows execution after debounce expires`() =
        runTest {
            val now = 100000L
            val last = now - 6000L // 6 seconds ago (past 5s debounce)
            savedStateHandle["last_resume_timestamp"] = last
            whenever(timeProvider.elapsedRealtime()).thenReturn(now)
            initializationState.value = InitializationState.Completed
            savedStateHandle[MainViewModel.KEY_LAST_REFRESH_TIME] = 0L

            var refreshCalled = false
            controller.onActivityResume(
                scope = backgroundScope,
                initController = initController,
                onRefreshNeeded = {
                    refreshCalled = true
                    true
                },
                onUpdateLastRefreshAt = {},
            )
            runCurrent()

            assertTrue("Should allow execution after debounce expires", refreshCalled)
            assertEquals("Should update timestamp", now, savedStateHandle.get<Long>("last_resume_timestamp"))
        }

    @Test
    fun `concurrent onActivityResume calls cancel previous outer job and refresh count does not exceed one`() =
        runTest {
            val now = 100000L
            whenever(timeProvider.elapsedRealtime()).thenReturn(now)
            initializationState.value = InitializationState.Completed
            savedStateHandle[MainViewModel.KEY_LAST_REFRESH_TIME] = 0L

            var refreshCount = 0
            val block: suspend () -> Boolean = {
                refreshCount++
                true
            }

            controller.onActivityResume(
                scope = backgroundScope,
                initController = initController,
                onRefreshNeeded = block,
                onUpdateLastRefreshAt = {},
            )

            // Clear timestamp to bypass debounce on second call
            savedStateHandle["last_resume_timestamp"] = -1L

            controller.onActivityResume(
                scope = backgroundScope,
                initController = initController,
                onRefreshNeeded = block,
                onUpdateLastRefreshAt = {},
            )

            runCurrent()

            assertEquals("Refresh count should not exceed one", 1, refreshCount)
        }
}
