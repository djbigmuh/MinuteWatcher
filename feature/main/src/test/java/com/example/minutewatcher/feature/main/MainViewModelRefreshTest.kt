package com.example.minutewatcher.feature.main

import com.example.minutewatcher.core.domain.RefreshResult
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SchemaVersion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.minutes

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelRefreshTest : MainViewModelTestFixtures() {
    @Test
    fun `onActivityResume triggers refresh on reboot (negative elapsed)`() =
        testScope.runTest {
            val viewModel = createViewModel(backgroundScope)
            testScheduler.advanceUntilIdle() // Let initializeApp() finish
            clearInvocations(usageStateRepository)

            savedStateHandle[MainViewModel.KEY_LAST_REFRESH_ELAPSED] = 500_000L
            timeProvider.setRealtime(1000L)
            viewModel.onActivityResume()
            testScheduler.advanceUntilIdle()

            verify(usageStateRepository).refresh()
        }

    @Test
    fun `onActivityResume triggers retry if initial refresh failed`() =
        testScope.runTest {
            whenever(usageStateRepository.refresh()).thenReturn(null)

            val viewModel = createViewModel(backgroundScope)
            testScheduler.advanceUntilIdle()
            clearInvocations(usageStateRepository)

            timeProvider.setRealtime(1000L)
            viewModel.onActivityResume()
            testScheduler.advanceUntilIdle()

            // New behavior: retry on resume if failed. initialization controller does 3 internal retries.
            verify(usageStateRepository, times(3)).refresh()
        }

    @Test
    fun `onActivityResume does NOT trigger refresh if initial refresh succeeded`() =
        testScope.runTest {
            val snapshot = GlobalSnapshot(SchemaVersion(1, 0, 0), 1000L, emptyList(), emptyList())
            whenever(usageStateRepository.refresh()).thenReturn(RefreshResult.Success(snapshot))

            val viewModel = createViewModel(backgroundScope)
            testScheduler.advanceUntilIdle()

            assertTrue(
                "lastRefreshAt should be set",
                (savedStateHandle.get<Long>(MainViewModel.KEY_LAST_REFRESH_ELAPSED) ?: 0L) > 0,
            )
            clearInvocations(usageStateRepository)

            timeProvider.setRealtime(timeProvider.elapsedRealtime() + 5000L)
            viewModel.onActivityResume()
            testScheduler.advanceUntilIdle()

            verify(usageStateRepository, never()).refresh()
        }

    @Test
    fun `refreshSimCardsNow calls onComplete even on failure`() =
        testScope.runTest {
            whenever(usageUseCase.refreshOperatorInfo()).thenThrow(RuntimeException("Fail"))
            val viewModel = createViewModel(backgroundScope)

            var completed = false
            viewModel.refreshSimCardsNow { completed = true }
            testScheduler.advanceUntilIdle()

            assertTrue("onComplete should be called even on failure", completed)
        }

    @Test
    fun `refreshAndNotify does NOT update lastRefreshAt on Timeout`() =
        testScope.runTest {
            val initialSnapshot =
                GlobalSnapshot(
                    version = SchemaVersion(1, 0, 0),
                    timestamp = 0L,
                    simSnapshots = emptyList(),
                    capabilities = emptyList(),
                )
            whenever(usageUseCase.refreshOperatorInfo()).thenAnswer { }
            whenever(usageStateRepository.refresh()).thenReturn(RefreshResult.Timeout(initialSnapshot))

            val viewModel = createViewModel(backgroundScope)
            testScheduler.advanceUntilIdle()

            savedStateHandle[MainViewModel.KEY_LAST_REFRESH_ELAPSED] = 0L
            val now = 2000000L
            timeProvider.setRealtime(now)

            viewModel.refreshSimCardsNow()
            testScheduler.advanceUntilIdle()

            assertEquals(0L, savedStateHandle.get<Long>(MainViewModel.KEY_LAST_REFRESH_ELAPSED))
        }

    @Test
    fun `onActivityResume triggers retry after a Timeout in initial refresh`() =
        testScope.runTest {
            val initialSnapshot =
                GlobalSnapshot(
                    version = SchemaVersion(1, 0, 0),
                    timestamp = 0L,
                    simSnapshots = emptyList(),
                    capabilities = emptyList(),
                )
            whenever(usageUseCase.refreshOperatorInfo()).thenReturn(Unit)
            whenever(usageStateRepository.refresh()).thenReturn(RefreshResult.Timeout(initialSnapshot))

            val viewModel = createViewModel(backgroundScope)
            testScheduler.advanceUntilIdle()

            viewModel.refreshSimCardsNow()
            testScheduler.advanceUntilIdle()

            val lastRefresh = savedStateHandle.get<Long>(MainViewModel.KEY_LAST_REFRESH_ELAPSED) ?: 0L
            assertEquals("lastRefreshAt should NOT be set after Timeout", 0L, lastRefresh)

            clearInvocations(usageStateRepository)

            timeProvider.setRealtime(5000L)
            viewModel.onActivityResume()
            testScheduler.advanceUntilIdle()

            // After Timeout, data is considered stale (lastRefreshAt is 0), so it should refresh.
            // initialization controller does 3 internal retries.
            verify(usageStateRepository, times(3)).refresh()
        }

    @Test
    fun `onActivityResume does not trigger refresh after recent manual pull-to-refresh`() =
        testScope.runTest {
            val initialSnapshot =
                GlobalSnapshot(
                    version = SchemaVersion(1, 0, 0),
                    timestamp = 1000L,
                    simSnapshots = emptyList(),
                    capabilities = emptyList(),
                )
            whenever(usageUseCase.refreshOperatorInfo()).thenReturn(Unit)
            whenever(usageStateRepository.refresh()).thenReturn(RefreshResult.Success(initialSnapshot))

            val viewModel = createViewModel(backgroundScope)
            testScheduler.advanceUntilIdle()

            viewModel.refreshSimCardsNow()
            testScheduler.advanceUntilIdle()

            val lastRefresh = savedStateHandle.get<Long>(MainViewModel.KEY_LAST_REFRESH_ELAPSED) ?: 0L
            assertTrue("lastRefreshAt should be set after pull-to-refresh", lastRefresh > 0)

            clearInvocations(usageStateRepository)

            val now = lastRefresh + 5000L
            timeProvider.setRealtime(now)
            viewModel.onActivityResume()
            testScheduler.advanceUntilIdle()

            verify(usageStateRepository, never()).refresh()
        }

    @Test
    fun `refreshAndNotify handles concurrent calls consistently`() =
        testScope.runTest {
            val snapshot1 =
                GlobalSnapshot(
                    version = SchemaVersion(1, 0, 0),
                    timestamp = 1L,
                    simSnapshots = emptyList(),
                    capabilities = emptyList(),
                )
            val snapshot2 =
                GlobalSnapshot(
                    version = SchemaVersion(1, 0, 0),
                    timestamp = 2L,
                    simSnapshots =
                        listOf(
                            com.example.minutewatcher.core.model.SimSnapshot(
                                slot = 0,
                                identity = "sim_0",
                                operatorName = "Test",
                                isActive = true,
                                entities = emptyMap(),
                                autoResetDay = 1,
                            ),
                        ),
                    capabilities = emptyList(),
                )

            whenever(usageUseCase.refreshOperatorInfo()).thenReturn(Unit)
            whenever(usageStateRepository.refresh()).thenReturn(RefreshResult.Success(snapshot1))

            val viewModel = createViewModel(backgroundScope)
            testScheduler.advanceUntilIdle()

            clearInvocations(dataChangeNotifier)

            whenever(usageStateRepository.refresh()).thenReturn(RefreshResult.Success(snapshot2))

            launch { viewModel.refreshSimCardsNow() }
            launch { viewModel.refreshSimCardsNow() }

            testScheduler.advanceUntilIdle()

            verify(dataChangeNotifier, times(2)).notifyDataChanged(eq(true), eq(false))
        }

    @Test
    fun `autoResetScheduler uses monotonic time and handles reboot`() =
        testScope.runTest {
            whenever(autoResetUseCase.performAutoReset(any())).thenReturn(Unit)
            createViewModel(backgroundScope)
            testScheduler.advanceUntilIdle()

            verify(autoResetUseCase).performAutoReset(eq(false))
            clearInvocations(autoResetUseCase)

            testScheduler.advanceTimeBy(30.minutes)

            createViewModel(backgroundScope)
            testScheduler.advanceUntilIdle()

            verify(autoResetUseCase, never()).performAutoReset(any())

            savedStateHandle["last_auto_reset_check"] = 10_000_000L

            createViewModel(backgroundScope)
            testScheduler.advanceUntilIdle()

            verify(autoResetUseCase).performAutoReset(eq(false))
        }

    @Test
    fun `autoResetScheduler can be retried after failure`() =
        testScope.runTest {
            org.mockito.kotlin
                .doThrow(RuntimeException("First failure"))
                .whenever(autoResetUseCase)
                .performAutoReset(eq(false))

            createViewModel(backgroundScope)
            testScheduler.advanceUntilIdle()

            // AutoResetScheduler is private in MainViewModel but it's initialized in setup.
            // For testing the scheduler specifically, we might need to use its own test class
            // or reflectively access it. In MainViewModelTest, it was testing viaVM creation.
            // Let's reuse the logic from MainViewModelTest.

            val scheduler =
                com.example.minutewatcher.feature.main.controller.AutoResetScheduler(
                    savedStateHandle,
                    autoResetUseCase,
                    timeProvider,
                )

            kotlin.runCatching { scheduler.checkOnStart() }

            verify(autoResetUseCase, times(2))
                .performAutoReset(eq(false)) // One from VM init, one from scheduler.checkOnStart

            org.mockito.kotlin
                .doReturn(Unit)
                .whenever(autoResetUseCase)
                .performAutoReset(eq(false))

            scheduler.checkOnStart()

            verify(autoResetUseCase, times(3)).performAutoReset(eq(false))
        }
}
