package host.serv.djbigmuh.minutewatcher.feature.main

import host.serv.djbigmuh.minutewatcher.core.domain.RefreshResult
import host.serv.djbigmuh.minutewatcher.core.model.GlobalSnapshot
import host.serv.djbigmuh.minutewatcher.core.model.SchemaVersion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelRefreshTest : MainViewModelTestFixtures() {
    @Test
    fun `onActivityResume triggers refresh on reboot (negative elapsed)`() =
        testScope.runTest {
            val viewModel = createViewModel(backgroundScope)
            testScheduler.advanceUntilIdle() // Let initializeApp() finish
            clearInvocations(usageStateRepository)

            savedStateHandle[MainViewModel.KEY_LAST_REFRESH_TIME] = 2_000_000L
            timeProvider.setTime(1000L)
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

            timeProvider.setTime(1000L)
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
                (savedStateHandle.get<Long>(MainViewModel.KEY_LAST_REFRESH_TIME) ?: 0L) > 0,
            )
            clearInvocations(usageStateRepository)

            timeProvider.setTime(timeProvider.now() + 5000L)
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

            savedStateHandle[MainViewModel.KEY_LAST_REFRESH_TIME] = 0L
            val now = 2000000L
            timeProvider.setTime(now)

            viewModel.refreshSimCardsNow()
            testScheduler.advanceUntilIdle()

            assertEquals(0L, savedStateHandle.get<Long>(MainViewModel.KEY_LAST_REFRESH_TIME))
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

            val lastRefresh = savedStateHandle.get<Long>(MainViewModel.KEY_LAST_REFRESH_TIME) ?: 0L
            assertEquals("lastRefreshAt should NOT be set after Timeout", 0L, lastRefresh)

            clearInvocations(usageStateRepository)

            timeProvider.setTime(5000L)
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

            val lastRefresh = savedStateHandle.get<Long>(MainViewModel.KEY_LAST_REFRESH_TIME) ?: 0L
            assertTrue("lastRefreshAt should be set after pull-to-refresh", lastRefresh > 0)

            clearInvocations(usageStateRepository)

            val now = lastRefresh + 5000L
            timeProvider.setTime(now)
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
                            host.serv.djbigmuh.minutewatcher.core.model.SimSnapshot(
                                slot = 0,
                                identity = "sim_0",
                                operatorName = "Test",
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

            verify(dataChangeNotifier, times(2)).notifyDataChanged(
                updateWidget = eq(value = true),
                force = eq(value = false),
            )
        }

    @Test
    fun `isRefreshing is true during refresh and false after successful refresh`() =
        testScope.runTest {
            val snapshot = GlobalSnapshot(SchemaVersion(1, 0, 0), 1000L, emptyList(), emptyList())
            whenever(usageUseCase.refreshOperatorInfo()).thenReturn(Unit)
            whenever(usageStateRepository.refresh()).thenReturn(RefreshResult.Success(snapshot))

            val viewModel = createViewModel(backgroundScope)
            testScheduler.advanceUntilIdle()

            val refreshStarted = CompletableDeferred<Unit>()
            val canCompleteRefresh = CompletableDeferred<Unit>()

            whenever(usageStateRepository.refresh()).doSuspendableAnswer {
                refreshStarted.complete(Unit)
                withTimeoutOrNull(30.seconds) { canCompleteRefresh.await() }
                    ?: throw AssertionError("Timed out waiting for canCompleteRefresh")
                RefreshResult.Success(snapshot)
            }

            backgroundScope.launch { viewModel.isRefreshing.collect {} }

            assertFalse("isRefreshing should initially be false", viewModel.isRefreshing.value)

            val refreshJob = launch { viewModel.refreshSimCardsNow() }

            testScheduler.runCurrent()
            withTimeout(5.seconds) {
                refreshStarted.await()
            }

            assertTrue("isRefreshing should be true during refresh", viewModel.isRefreshing.value)

            canCompleteRefresh.complete(Unit)
            testScheduler.advanceUntilIdle()

            withTimeout(5.seconds) {
                refreshJob.join()
            }

            assertFalse("isRefreshing should be false after successful refresh", viewModel.isRefreshing.value)
        }

    @Test
    fun `isRefreshing returns to false when refresh is SkippedAlreadyInProgress`() =
        testScope.runTest {
            val snapshot = GlobalSnapshot(SchemaVersion(1, 0, 0), 1000L, emptyList(), emptyList())
            whenever(usageUseCase.refreshOperatorInfo()).thenReturn(Unit)
            whenever(usageStateRepository.refresh()).thenReturn(RefreshResult.Success(snapshot))

            val viewModel = createViewModel(backgroundScope)
            testScheduler.advanceUntilIdle()

            backgroundScope.launch { viewModel.isRefreshing.collect {} }

            launch { viewModel.refreshSimCardsNow() }
            launch { viewModel.refreshSimCardsNow() } // second call gets SkippedAlreadyInProgress

            testScheduler.advanceUntilIdle()

            assertFalse("isRefreshing should be false after SkippedAlreadyInProgress", viewModel.isRefreshing.value)
        }

    @Test
    fun `isRefreshing returns to false when refresh fails with error`() =
        testScope.runTest {
            whenever(usageUseCase.refreshOperatorInfo()).thenThrow(RuntimeException("Failure"))

            val viewModel = createViewModel(backgroundScope)
            testScheduler.advanceUntilIdle()

            backgroundScope.launch { viewModel.isRefreshing.collect {} }

            viewModel.refreshSimCardsNow()
            testScheduler.advanceUntilIdle()

            assertFalse("isRefreshing should be false after failure", viewModel.isRefreshing.value)
        }
}
