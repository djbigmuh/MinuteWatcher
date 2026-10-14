package com.example.minutewatcher.feature.widget

import android.content.Context
import com.example.minutewatcher.core.domain.WidgetUpdateResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
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
    fun `updateAll executes single pass with wasCoalesced false when no parallel calls`() =
        runTest {
            var renderCount = 0
            val fakeRenderer =
                object : WidgetRenderer {
                    override suspend fun renderAll(
                        context: Context,
                        forceFull: Boolean,
                    ) {
                        renderCount++
                    }
                }

            val updater = createUpdater(backgroundScope, fakeRenderer)
            val result = updater.updateAll()

            assertTrue(result.isSuccess)
            assertFalse((result as WidgetUpdateResult.Success).wasCoalesced)
            assertEquals(1, renderCount)
        }

    @Test
    fun `request arriving during running pass triggers tail pass and caller receives result of later pass`() =
        runTest {
            val startBarrier1 = CompletableDeferred<Unit>()
            val finishBarrier1 = CompletableDeferred<Unit>()
            val startBarrier2 = CompletableDeferred<Unit>()
            val finishBarrier2 = CompletableDeferred<Unit>()
            var renderCount = 0
            val passStartTimes = mutableListOf<Long>()

            val fakeRenderer =
                object : WidgetRenderer {
                    override suspend fun renderAll(
                        context: Context,
                        forceFull: Boolean,
                    ) {
                        renderCount++
                        passStartTimes.add(System.currentTimeMillis())
                        if (renderCount == 1) {
                            startBarrier1.complete(Unit)
                            withTimeoutOrNull(30.seconds) { finishBarrier1.await() }
                        } else {
                            startBarrier2.complete(Unit)
                            withTimeoutOrNull(30.seconds) { finishBarrier2.await() }
                        }
                    }
                }

            val updater = createUpdater(backgroundScope, fakeRenderer)

            val result1Deferred = async { updater.updateAll() }

            withTimeoutOrNull(30.seconds) { startBarrier1.await() }
                ?: throw AssertionError("Timed out waiting for startBarrier1")

            // Arrive during running pass 1 -> schedules tail pass
            val result2Deferred = async { updater.updateAll() }

            finishBarrier1.complete(Unit)

            withTimeoutOrNull(30.seconds) { startBarrier2.await() }
                ?: throw AssertionError("Timed out waiting for startBarrier2")
            finishBarrier2.complete(Unit)

            val result1 = result1Deferred.await()
            val result2 = result2Deferred.await()

            assertTrue(result1.isSuccess)
            assertTrue(result2.isSuccess)
            assertFalse((result1 as WidgetUpdateResult.Success).wasCoalesced)
            assertTrue((result2 as WidgetUpdateResult.Success).wasCoalesced)
            assertEquals(2, renderCount)
        }

    @Test
    fun `multiple parallel requests during running pass are grouped into exactly one tail pass`() =
        runTest {
            val startBarrier1 = CompletableDeferred<Unit>()
            val finishBarrier1 = CompletableDeferred<Unit>()
            val startBarrier2 = CompletableDeferred<Unit>()
            val finishBarrier2 = CompletableDeferred<Unit>()
            var renderCount = 0

            val fakeRenderer =
                object : WidgetRenderer {
                    override suspend fun renderAll(
                        context: Context,
                        forceFull: Boolean,
                    ) {
                        renderCount++
                        if (renderCount == 1) {
                            startBarrier1.complete(Unit)
                            withTimeoutOrNull(30.seconds) { finishBarrier1.await() }
                        } else {
                            startBarrier2.complete(Unit)
                            withTimeoutOrNull(30.seconds) { finishBarrier2.await() }
                        }
                    }
                }

            val updater = createUpdater(backgroundScope, fakeRenderer)

            val result1Deferred = async { updater.updateAll() }
            withTimeoutOrNull(30.seconds) { startBarrier1.await() }

            // Multiple concurrent requests during pass 1
            val result2Deferred = async { updater.updateAll() }
            val result3Deferred = async { updater.updateAll() }
            val result4Deferred = async { updater.updateAll() }

            finishBarrier1.complete(Unit)
            withTimeoutOrNull(30.seconds) { startBarrier2.await() }
            finishBarrier2.complete(Unit)

            val r1 = result1Deferred.await()
            val r2 = result2Deferred.await()
            val r3 = result3Deferred.await()
            val r4 = result4Deferred.await()

            assertTrue(r1.isSuccess)
            assertTrue(r2.isSuccess)
            assertTrue(r3.isSuccess)
            assertTrue(r4.isSuccess)

            assertEquals("Renderer should be called exactly twice (1 main + 1 tail)", 2, renderCount)
        }

    @Test
    fun `cancellation of main pass coroutine when tail pass is scheduled does not block waiters`() =
        runTest {
            val startBarrier1 = CompletableDeferred<Unit>()
            val finishBarrier1 = CompletableDeferred<Unit>()
            val startBarrier2 = CompletableDeferred<Unit>()
            val finishBarrier2 = CompletableDeferred<Unit>()
            var renderCount = 0

            val fakeRenderer =
                object : WidgetRenderer {
                    override suspend fun renderAll(
                        context: Context,
                        forceFull: Boolean,
                    ) {
                        renderCount++
                        if (renderCount == 1) {
                            startBarrier1.complete(Unit)
                            withTimeoutOrNull(30.seconds) { finishBarrier1.await() }
                        } else {
                            startBarrier2.complete(Unit)
                            withTimeoutOrNull(30.seconds) { finishBarrier2.await() }
                        }
                    }
                }

            val updater = createUpdater(backgroundScope, fakeRenderer)

            val primaryJob = async { updater.updateAll() }
            withTimeoutOrNull(30.seconds) { startBarrier1.await() }

            val waitingDeferred = async { updater.updateAll() }

            // Cancel primary job while running
            primaryJob.cancel()
            finishBarrier1.complete(Unit)

            withTimeoutOrNull(30.seconds) { startBarrier2.await() }
            finishBarrier2.complete(Unit)

            val result = waitingDeferred.await()
            assertTrue(result.isSuccess)
            assertTrue((result as WidgetUpdateResult.Success).wasCoalesced)
        }

    @Test(expected = CancellationException::class)
    fun `cancellation of one waiting caller propagates CancellationException to it without affecting passes`() =
        runTest {
            val startBarrier1 = CompletableDeferred<Unit>()
            val finishBarrier1 = CompletableDeferred<Unit>()
            var renderCount = 0

            val fakeRenderer =
                object : WidgetRenderer {
                    override suspend fun renderAll(
                        context: Context,
                        forceFull: Boolean,
                    ) {
                        renderCount++
                        startBarrier1.complete(Unit)
                        withTimeoutOrNull(30.seconds) { finishBarrier1.await() }
                    }
                }

            val updater = createUpdater(backgroundScope, fakeRenderer)

            launch { updater.updateAll() }
            withTimeoutOrNull(30.seconds) { startBarrier1.await() }

            val waitingJob = async { updater.updateAll() }
            waitingJob.cancel()

            finishBarrier1.complete(Unit)
            advanceUntilIdle()

            waitingJob.await()
        }
}
