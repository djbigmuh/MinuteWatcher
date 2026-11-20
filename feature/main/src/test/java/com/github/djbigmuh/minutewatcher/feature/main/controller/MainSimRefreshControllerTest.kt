package com.github.djbigmuh.minutewatcher.feature.main.controller

import com.github.djbigmuh.minutewatcher.core.domain.RefreshResult
import com.github.djbigmuh.minutewatcher.core.domain.UsageStateRepository
import com.github.djbigmuh.minutewatcher.core.domain.UsageUseCase
import com.github.djbigmuh.minutewatcher.core.model.GlobalSnapshot
import com.github.djbigmuh.minutewatcher.core.model.SchemaVersion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class MainSimRefreshControllerTest {
    private val usageUseCase: UsageUseCase = mock()
    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun `refreshSimCardsInternal returns SkippedAlreadyInProgress on contention`() =
        runTest(testDispatcher) {
            val snapshot = GlobalSnapshot(SchemaVersion(1, 0, 0), 1000L, emptyList(), emptyList())
            val refreshDeferred = CompletableDeferred<RefreshResult>()

            val fakeRepository =
                object : UsageStateRepository by mock() {
                    override suspend fun refresh(): RefreshResult =
                        withTimeoutOrNull(30.seconds) { refreshDeferred.await() }
                            ?: throw AssertionError("Timed out waiting for refreshDeferred in fakeRepository")
                }

            val controller =
                MainSimRefreshController(
                    usageUseCase,
                    fakeRepository,
                    testDispatcher,
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
