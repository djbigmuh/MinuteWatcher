package com.example.minutewatcher.feature.widget

import android.content.Context
import android.os.TransactionTooLargeException
import com.example.minutewatcher.core.domain.WidgetUpdateResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetUpdaterCoalescingTest : BaseWidgetUpdaterFixture() {
    @Test
    fun `updateAll coalesces concurrent calls`() =
        runTest {
            val startBarrier = CompletableDeferred<Unit>()
            val finishBarrier = CompletableDeferred<Unit>()
            var renderCount = 0

            val fakeRenderer =
                object : WidgetRenderer {
                    override suspend fun renderAll(
                        context: Context,
                        forceFull: Boolean,
                    ) {
                        renderCount++
                        startBarrier.complete(Unit)
                        withTimeoutOrNull(30.seconds) { finishBarrier.await() }
                            ?: throw AssertionError("Timed out waiting for finishBarrier")
                    }
                }

            val updater = createUpdater(backgroundScope, fakeRenderer)

            val result1Deferred = async { updater.updateAll() }

            withTimeoutOrNull(30.seconds) { startBarrier.await() }
                ?: throw AssertionError("Timed out waiting for startBarrier")

            val result2Deferred = async { updater.updateAll() }

            finishBarrier.complete(Unit)

            val result1 =
                withTimeoutOrNull(30.seconds) { result1Deferred.await() }
                    ?: throw AssertionError("Timed out waiting for result1")
            val result2 =
                withTimeoutOrNull(30.seconds) { result2Deferred.await() }
                    ?: throw AssertionError("Timed out waiting for result2")

            assertTrue("Both calls should succeed", result1.isSuccess && result2.isSuccess)
            assertEquals("Render should be called only once", 1, renderCount)

            val results = listOf(result1, result2)
            val successResults = results.filterIsInstance<WidgetUpdateResult.Success>()
            assertEquals(2, successResults.size)
            assertTrue("At least one update should be coalesced", successResults.any { it.wasCoalesced })
            assertTrue("At least one update should NOT be coalesced", successResults.any { !it.wasCoalesced })
        }

    @Test
    fun `updateAll coalesced calls receive success if recovery succeeds`() =
        runTest {
            val startBarrier = CompletableDeferred<Unit>()
            val finishBarrier = CompletableDeferred<Unit>()
            var callCount = 0

            val fakeRenderer =
                object : WidgetRenderer {
                    override suspend fun renderAll(
                        context: Context,
                        forceFull: Boolean,
                    ) {
                        callCount++
                        if (callCount == 1) {
                            startBarrier.complete(Unit)
                            withTimeoutOrNull(30.seconds) { finishBarrier.await() }
                                ?: throw AssertionError("Timed out waiting for finishBarrier")
                            throw IllegalArgumentException("Too large", TransactionTooLargeException())
                        }
                    }
                }

            val updaterWithFake = createUpdater(backgroundScope, fakeRenderer)

            val result1Deferred = async { updaterWithFake.updateAll() }

            withTimeoutOrNull(30.seconds) { startBarrier.await() }
                ?: throw AssertionError("Timed out waiting for startBarrier")

            val result2Deferred = async { updaterWithFake.updateAll() }

            finishBarrier.complete(Unit)

            val result1 =
                withTimeoutOrNull(30.seconds) { result1Deferred.await() }
                    ?: throw AssertionError("Timed out waiting for result1Deferred")
            val result2 =
                withTimeoutOrNull(30.seconds) { result2Deferred.await() }
                    ?: throw AssertionError("Timed out waiting for result2Deferred")

            assertTrue("Original update should succeed after recovery", result1.isSuccess)
            assertTrue("Joined update should succeed after recovery", result2.isSuccess)

            val success1 = result1 as WidgetUpdateResult.Success
            val success2 = result2 as WidgetUpdateResult.Success

            assertFalse("Result 1 should NOT be coalesced", success1.wasCoalesced)
            assertTrue("Result 2 SHOULD be coalesced", success2.wasCoalesced)

            assertEquals("Should have called renderer for full attempt and simplified recovery", 2, callCount)
        }

    @Test
    fun `updateAll concurrent call at the end of previous update does not return failure`() =
        runTest {
            val finishBarrier = CompletableDeferred<Unit>()
            val fakeRenderer =
                object : WidgetRenderer {
                    override suspend fun renderAll(
                        context: Context,
                        forceFull: Boolean,
                    ) {
                        withTimeoutOrNull(30.seconds) { finishBarrier.await() }
                            ?: throw AssertionError("Timed out waiting for finishBarrier")
                    }
                }

            val updater = createUpdater(backgroundScope, fakeRenderer)

            val result1Deferred = async { updater.updateAll() }

            finishBarrier.complete(Unit)
            val result2 = updater.updateAll()
            val result1 =
                withTimeoutOrNull(30.seconds) { result1Deferred.await() }
                    ?: throw AssertionError("Timed out waiting for result1Deferred")

            assertTrue("First update should succeed", result1.isSuccess)
            assertTrue("Second update should succeed", result2.isSuccess)
        }

    @Test
    fun `updateAll coalesced calls receive failure instead of cancellation when primary is cancelled`() =
        runTest {
            val startBarrier = CompletableDeferred<Unit>()
            val finishBarrier = CompletableDeferred<Unit>()

            val fakeRenderer =
                object : WidgetRenderer {
                    override suspend fun renderAll(
                        context: Context,
                        forceFull: Boolean,
                    ) {
                        startBarrier.complete(Unit)
                        withTimeoutOrNull(30.seconds) { finishBarrier.await() }
                            ?: throw AssertionError("Timed out waiting for finishBarrier")
                    }
                }

            val updater = createUpdater(backgroundScope, fakeRenderer)

            val primaryJob =
                backgroundScope.launch {
                    updater.updateAll()
                }

            withTimeoutOrNull(30.seconds) { startBarrier.await() }
                ?: throw AssertionError("Timed out waiting for startBarrier")

            val coalescedDeferred =
                backgroundScope.async {
                    updater.updateAll()
                }
            runCurrent()

            primaryJob.cancel()
            runCurrent()
            advanceUntilIdle()

            val result =
                withTimeoutOrNull(30.seconds) { coalescedDeferred.await() }
                    ?: throw AssertionError("Timed out waiting for coalescedDeferred")
            assertTrue("Result should be failure", result is WidgetUpdateResult.Failure)
            val failure = result as WidgetUpdateResult.Failure
            assertTrue("Result should be marked as coalesced", failure.wasCoalesced)
            assertEquals("Reason should be TRANSIENT", WidgetUpdateResult.FailureReason.TRANSIENT, failure.reason)
        }

    @Test(expected = CancellationException::class)
    fun `updateAll propagates CancellationException when joined caller is cancelled`() =
        runTest {
            val startBarrier = CompletableDeferred<Unit>()
            val finishBarrier = CompletableDeferred<Unit>()

            val fakeRenderer =
                object : WidgetRenderer {
                    override suspend fun renderAll(
                        context: Context,
                        forceFull: Boolean,
                    ) {
                        startBarrier.complete(Unit)
                        withTimeoutOrNull(30.seconds) { finishBarrier.await() }
                            ?: throw AssertionError("Timed out waiting for finishBarrier")
                    }
                }

            val updater = createUpdater(backgroundScope, fakeRenderer)

            launch { updater.updateAll() }
            withTimeoutOrNull(30.seconds) { startBarrier.await() }
                ?: throw AssertionError("Timed out waiting for startBarrier")

            val joinedUpdateJob = async { updater.updateAll() }
            joinedUpdateJob.cancel()
            joinedUpdateJob.await()
        }
}
