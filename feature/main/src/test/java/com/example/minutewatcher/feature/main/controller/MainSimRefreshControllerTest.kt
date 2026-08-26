package com.example.minutewatcher.feature.main.controller

import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.domain.RefreshResult
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.UsageUseCase
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SchemaVersion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class MainSimRefreshControllerTest {
    private val usageUseCase: UsageUseCase = mock()
    private val usageStateRepository: UsageStateRepository = mock()
    private val timeProvider: TimeProvider = mock()
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var controller: MainSimRefreshController

    @Before
    fun setup() {
        controller =
            MainSimRefreshController(
                usageUseCase,
                usageStateRepository,
                testDispatcher,
                timeProvider,
            )
    }

    @Test
    fun `refreshSimCardsInternal returns SkippedAlreadyInProgress on contention`() =
        runTest(testDispatcher) {
            val snapshot = GlobalSnapshot(SchemaVersion(1, 0, 0), 1000L, emptyList(), emptyList())
            val refreshDeferred = CompletableDeferred<RefreshResult>()

            whenever(usageStateRepository.refresh()).thenAnswer {
                // Mocking a suspending function that waits for deferred
                // Using runBlocking is bad, but here we are in a test and it's a suspending context?
                // No, thenAnswer is not suspending.
                // We should use doAnswer { ... } and it might work if we use it correctly.
                // Or better: use a fake implementation of UsageStateRepository.
                throw IllegalStateException("Should not be called directly")
            }

            // Let's use a fake implementation instead of mock for the suspending call
            val fakeRepository =
                object : UsageStateRepository by mock() {
                    override suspend fun refresh(): RefreshResult = refreshDeferred.await()
                }

            controller =
                MainSimRefreshController(
                    usageUseCase,
                    fakeRepository,
                    testDispatcher,
                    timeProvider,
                )

            // Launch first refresh
            val firstResult = async { controller.refreshSimCardsInternal() }
            testScheduler.runCurrent() // Allow first refresh to reach the mutex lock

            // Launch second refresh while first is in progress
            val secondResult = async { controller.refreshSimCardsInternal() }
            testScheduler.runCurrent()

            // Assert second result is Skipped
            assertTrue(
                "Second call should be skipped",
                secondResult.getCompleted() is MainSimRefreshController.RefreshOutcome.SkippedAlreadyInProgress,
            )

            // Complete first refresh
            refreshDeferred.complete(RefreshResult.Success(snapshot))
            testScheduler.runCurrent()

            // Assert first result is Completed
            val firstOutcome = firstResult.getCompleted()
            assertTrue(
                "First call should be completed",
                firstOutcome is MainSimRefreshController.RefreshOutcome.Completed,
            )
            assertTrue(
                (firstOutcome as MainSimRefreshController.RefreshOutcome.Completed).result is RefreshResult.Success,
            )
        }
}
