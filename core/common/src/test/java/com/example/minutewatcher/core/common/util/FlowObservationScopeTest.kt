package com.example.minutewatcher.core.common.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class FlowObservationScopeTest {
    @Test
    fun `observe triggers collector on emissions`() =
        runTest {
            val scope = FlowObservationScope<Int>(backgroundScope, "test")
            val flow = MutableSharedFlow<Int>()
            val values = mutableListOf<Int>()

            scope.observe(flow) { values.add(it) }
            runCurrent()

            flow.emit(1)
            runCurrent()
            assertEquals(listOf(1), values)

            flow.emit(2)
            runCurrent()
            assertEquals(listOf(1, 2), values)
        }

    @Test
    fun `repeated observe replaces previous observation`() =
        runTest {
            val scope = FlowObservationScope<Int>(backgroundScope, "test")
            val flow1 = MutableSharedFlow<Int>()
            val values1 = mutableListOf<Int>()
            val flow2 = MutableSharedFlow<Int>()
            val values2 = mutableListOf<Int>()

            val job1 = scope.observe(flow1) { values1.add(it) }
            runCurrent()

            scope.observe(flow2) { values2.add(it) }
            runCurrent()

            assertTrue("First job should be cancelled", job1?.isCancelled == true)

            flow1.emit(1)
            flow2.emit(10)
            runCurrent()

            assertEquals("Old collector should not receive values", emptyList<Int>(), values1)
            assertEquals("New collector should receive values", listOf(10), values2)
        }

    @Test
    fun `collectLatest semantics - only latest value is processed`() =
        runTest {
            val scope = FlowObservationScope<Int>(backgroundScope, "test")
            val flow = MutableSharedFlow<Int>(replay = 1)
            val processedValues = mutableListOf<Int>()
            val startCount = AtomicInteger(0)

            scope.observe(flow) { value ->
                startCount.incrementAndGet()
                delay(100.milliseconds) // Simulate long processing
                processedValues.add(value)
            }

            runCurrent() // Start collection

            flow.emit(1)
            runCurrent()
            flow.emit(2)
            runCurrent()
            flow.emit(3)
            runCurrent()

            advanceTimeBy(500.milliseconds)
            runCurrent()

            assertEquals(listOf(3), processedValues)
            assertEquals(3, startCount.get())
        }

    @Test
    fun `recover uses collectLatest and restarts last observation`() =
        runTest {
            val scope = FlowObservationScope<Int>(backgroundScope, "test")
            val flow = MutableSharedFlow<Int>(replay = 1)
            val processedValues = mutableListOf<Int>()

            scope.observe(flow) { value ->
                delay(100.milliseconds)
                processedValues.add(value)
            }

            runCurrent()

            flow.emit(1)
            advanceTimeBy(150.milliseconds)
            assertEquals(listOf(1), processedValues)

            scope.recover()
            runCurrent()

            flow.emit(2)
            runCurrent()
            flow.emit(3)
            runCurrent()

            advanceTimeBy(200.milliseconds)
            runCurrent()

            assertEquals(listOf(1, 3), processedValues)
        }

    @Test
    fun `stopAndAwait cancels collector and returns true when finished in time`() =
        runTest {
            val scope = FlowObservationScope<Int>(backgroundScope, "test")
            val flow = MutableSharedFlow<Int>()
            var cancelled = false

            scope.observe(flow) {
                try {
                    delay(10000)
                } finally {
                    cancelled = true
                }
            }
            runCurrent()
            flow.emit(1)
            runCurrent()

            val result = scope.stopAndAwait(100)
            assertTrue("Should return true on successful completion", result)
            assertTrue("Collector should be cancelled", cancelled)
            assertNull("currentJob should be null", scope.currentJob)
        }

    @Test
    fun `stopAndAwait returns false and clears reference when collector is stuck`() =
        runTest {
            val scope = FlowObservationScope<Int>(backgroundScope, "test")
            val flow = MutableSharedFlow<Int>()
            val reachedCleanup = CompletableDeferred<Unit>()

            scope.observe(flow) {
                reachedCleanup.complete(Unit)
                withContext(NonCancellable) {
                    delay(1000)
                }
            }
            runCurrent()
            flow.emit(1)
            runCurrent()
            reachedCleanup.await()

            val result = scope.stopAndAwait(10)
            assertFalse("Should return false on timeout", result)
            assertNull("Reference should be cleared even on timeout", scope.currentJob)
        }

    @Test
    fun `stopAndAwait propagates external cancellation`() =
        runTest {
            val scope = FlowObservationScope<Int>(backgroundScope, "test")
            val flow = MutableSharedFlow<Int>(replay = 1)
            flow.emit(1)

            scope.observe(flow) {
                withContext(NonCancellable) {
                    delay(10000)
                }
            }
            runCurrent()

            val stopJob =
                launch {
                    scope.stopAndAwait(5000)
                }
            runCurrent()
            assertFalse("stopJob should be suspended at job.join()", stopJob.isCompleted)

            stopJob.cancel()
            runCurrent()
            stopJob.join()

            assertTrue("Outer job should be cancelled", stopJob.isCancelled)
        }

    @Test
    fun `shutdown cancels current job and clears references`() =
        runTest {
            val scope = FlowObservationScope<Int>(backgroundScope, "test")
            val flow = MutableSharedFlow<Int>(replay = 1)
            val job = scope.observe(flow) { }
            runCurrent()

            scope.shutdown()
            runCurrent()

            assertTrue("Job should be cancelled", job?.isCancelled == true)
            assertNull("currentJob should be null", scope.currentJob)

            val recoverJob = scope.recover()
            assertNull("Recover should not start new job after shutdown", recoverJob)
        }

    @Test
    fun `currentJob returns active job`() =
        runTest {
            val scope = FlowObservationScope<Int>(backgroundScope, "test")
            assertNull(scope.currentJob)

            val flow = MutableSharedFlow<Int>()
            val job = scope.observe(flow) { }
            runCurrent()

            assertEquals(job, scope.currentJob)
            assertNotNull(scope.currentJob)
        }

    @Test
    fun `second observe waits for first collector to complete before receiving values`() =
        runTest {
            withTimeout(5000.milliseconds) {
                val scope = FlowObservationScope<Int>(backgroundScope, "test")
                val flow1 = MutableSharedFlow<Int>(replay = 1)
                val flow2 = MutableSharedFlow<Int>(replay = 1)
                val collector1Blocked = CompletableDeferred<Unit>()
                val collector1CanFinish = CompletableDeferred<Unit>()
                val collector2Values = mutableListOf<Int>()

                scope.observe(flow1) {
                    collector1Blocked.complete(Unit)
                    try {
                        delay(100000)
                    } finally {
                        withContext(NonCancellable) {
                            collector1CanFinish.await()
                        }
                    }
                }
                runCurrent()
                flow1.emit(1)
                runCurrent()
                collector1Blocked.await()

                flow2.emit(10)
                var observe2Job: Job? = null
                val secondObserveLaunch =
                    launch {
                        observe2Job = scope.observe(flow2) { collector2Values.add(it) }
                    }
                runCurrent()

                assertTrue(
                    "Second collector must not receive values while first is blocked",
                    collector2Values.isEmpty(),
                )

                collector1CanFinish.complete(Unit)
                runCurrent()
                secondObserveLaunch.join()
                runCurrent()

                assertEquals(listOf(10), collector2Values)
                assertNotNull(observe2Job)
            }
        }

    @Test
    fun `second observe proceeds and launches new job if first collector ignores cancellation and times out`() =
        runTest {
            withTimeout(15000.milliseconds) {
                val scope = FlowObservationScope<Int>(backgroundScope, "test")
                val flow1 = MutableSharedFlow<Int>(replay = 1)
                val flow2 = MutableSharedFlow<Int>(replay = 1)

                flow1.emit(1)
                scope.observe(flow1) {
                    withContext(NonCancellable) {
                        delay(10000.milliseconds)
                    }
                }
                runCurrent()

                flow2.emit(20)
                var newJob: Job? = null
                val secondObserveLaunch =
                    launch {
                        newJob = scope.observe(flow2) { }
                    }

                advanceTimeBy(3500.milliseconds)
                runCurrent()
                secondObserveLaunch.join()

                assertNotNull("New job must be launched after timeout even if previous stuck", newJob)
            }
        }

    @Test
    fun `observe after shutdown returns null and does not launch coroutine`() =
        runTest {
            withTimeout(5000.milliseconds) {
                val scope = FlowObservationScope<Int>(backgroundScope, "test")
                val flow = MutableSharedFlow<Int>()

                scope.shutdown()
                runCurrent()

                val job = scope.observe(flow) { }
                assertNull("observe after shutdown must return null", job)
            }
        }

    @Test
    fun `stopAndAwait returns true when no observer is active`() =
        runTest {
            withTimeout(5000.milliseconds) {
                val scope = FlowObservationScope<Int>(backgroundScope, "test")
                val result = scope.stopAndAwait(1000)
                assertTrue("stopAndAwait must return true when no job is active", result)
            }
        }

    @Test
    fun `consecutive recover calls do not create more than one active observer`() =
        runTest {
            withTimeout(5000.milliseconds) {
                val scope = FlowObservationScope<Int>(backgroundScope, "test")
                val flow = MutableSharedFlow<Int>(replay = 1)
                val collectorValues = mutableListOf<Int>()

                scope.observe(flow) { collectorValues.add(it) }
                runCurrent()

                val job1 = scope.recover()
                runCurrent()
                val job2 = scope.recover()
                runCurrent()

                assertEquals("Only the last job should be active in currentJob", job2, scope.currentJob)
                assertTrue("Previous job should be cancelled", job1?.isCancelled == true)
            }
        }
}
