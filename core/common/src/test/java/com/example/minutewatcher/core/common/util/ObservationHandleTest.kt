package com.example.minutewatcher.core.common.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ObservationHandleTest {
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var handle: ObservationHandle

    @Before
    fun setup() {
        handle = ObservationHandle(testScope, "test")
    }

    @Test
    fun `start triggers collector on emissions`() =
        runTest(testDispatcher) {
            val flow = MutableSharedFlow<Int>()
            val values = mutableListOf<Int>()

            handle.start(flow) { values.add(it) }
            runCurrent()

            flow.emit(1)
            runCurrent()
            assertEquals(listOf(1), values)

            flow.emit(2)
            runCurrent()
            assertEquals(listOf(1, 2), values)
        }

    @Test
    fun `repeated start replaces previous observation`() =
        runTest(testDispatcher) {
            val flow1 = MutableSharedFlow<Int>()
            val values1 = mutableListOf<Int>()
            val flow2 = MutableSharedFlow<Int>()
            val values2 = mutableListOf<Int>()

            val job1 = handle.start(flow1) { values1.add(it) }
            runCurrent()

            handle.start(flow2) { values2.add(it) }
            runCurrent()

            assertTrue("First job should be cancelled", job1.isCancelled)

            flow1.emit(1)
            flow2.emit(10)
            runCurrent()

            assertEquals("Old collector should not receive values", emptyList<Int>(), values1)
            assertEquals("New collector should receive values", listOf(10), values2)
        }

    @Test
    fun `stopAndAwait cancels collector and returns true when finished in time`() =
        runTest(testDispatcher) {
            val flow = MutableSharedFlow<Int>()
            var cancelled = false

            handle.start(flow) {
                try {
                    delay(10000)
                } finally {
                    cancelled = true
                }
            }
            runCurrent()
            flow.emit(1)
            runCurrent()

            val result = handle.stopAndAwait(100)
            assertTrue("Should return true on successful completion", result)
            assertTrue("Collector should be cancelled", cancelled)
            assertNull("currentJob should be null", handle.currentJob)
        }

    @Test
    fun `stopAndAwait returns false and clears reference when collector is stuck`() =
        runTest(testDispatcher) {
            val flow = MutableSharedFlow<Int>()
            val reachedCleanup = CompletableDeferred<Unit>()

            handle.start(flow) {
                reachedCleanup.complete(Unit)
                withContext(NonCancellable) {
                    delay(1000)
                }
            }
            runCurrent()
            flow.emit(1)
            runCurrent()
            reachedCleanup.await()

            // stopAndAwait with small timeout
            val result = handle.stopAndAwait(10)
            assertFalse("Should return false on timeout", result)
            assertNull("Reference should be cleared even on timeout", handle.currentJob)
        }

    @Test
    fun `stopAndAwait propagates external cancellation`() =
        runTest(testDispatcher) {
            val flow = MutableSharedFlow<Int>(replay = 1)
            flow.emit(1)

            // Use a job that ignores cancellation for a bit to ensure stopAndAwait stays suspended
            handle.start(flow) {
                withContext(NonCancellable) {
                    delay(10000)
                }
            }
            runCurrent()

            val stopJob =
                launch {
                    handle.stopAndAwait(5000)
                }
            runCurrent()
            assertFalse("stopJob should be suspended at job.join()", stopJob.isCompleted)

            stopJob.cancel()
            runCurrent()
            stopJob.join()

            assertTrue("Outer job should be cancelled", stopJob.isCancelled)
        }

    @Test
    fun `currentJob returns active job`() =
        runTest(testDispatcher) {
            assertNull(handle.currentJob)

            val flow = MutableSharedFlow<Int>()
            val job = handle.start(flow) { }
            runCurrent()

            assertEquals(job, handle.currentJob)
            assertNotNull(handle.currentJob)
        }
}
