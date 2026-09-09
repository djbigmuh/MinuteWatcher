package com.example.minutewatcher.core.common.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class FlowObservationScopeTest {
    @Test
    fun `collectLatest semantics - only latest value is processed`() =
        runTest {
            val scope = FlowObservationScope<Int>(backgroundScope, "test")
            // Use replay=1 to ensure values are not missed during setup
            val flow = MutableSharedFlow<Int>(replay = 1)
            val processedValues = mutableListOf<Int>()
            val startCount = AtomicInteger(0)

            scope.observe(flow) { value ->
                startCount.incrementAndGet()
                delay(100.milliseconds) // Simulate long processing
                processedValues.add(value)
            }

            runCurrent() // Start collection

            // Emit 3 values rapidly. Each emission should trigger a new lambda,
            // but collectLatest should cancel the previous one if it's still running.
            flow.emit(1)
            runCurrent()
            flow.emit(2)
            runCurrent()
            flow.emit(3)
            runCurrent()

            // Advance time to allow processing to finish
            advanceTimeBy(500.milliseconds)
            runCurrent()

            // Verification:
            // 1. Only the last value (3) should be fully processed.
            // 2. startCount should be 3 because all 3 were received.
            assertEquals(listOf(3), processedValues)
            assertEquals(3, startCount.get())
        }

    @Test
    fun `recover uses collectLatest`() =
        runTest {
            val scope = FlowObservationScope<Int>(backgroundScope, "test")
            val flow = MutableSharedFlow<Int>(replay = 1)
            val processedValues = mutableListOf<Int>()

            scope.observe(flow) { value ->
                delay(100.milliseconds)
                processedValues.add(value)
            }

            runCurrent()

            // Initial emission
            flow.emit(1)
            advanceTimeBy(150.milliseconds)
            assertEquals(listOf(1), processedValues)

            // Trigger recover
            scope.recover()
            runCurrent()

            // Rapid emissions after recover
            flow.emit(2)
            runCurrent()
            flow.emit(3)
            runCurrent()

            advanceTimeBy(200.milliseconds)
            runCurrent()

            // Only 3 should be added if recover uses collectLatest (replacing 2)
            assertEquals(listOf(1, 3), processedValues)
        }
}
