package host.serv.djbigmuh.minutewatcher.core.common.util

import host.serv.djbigmuh.minutewatcher.core.model.Constants
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
import org.junit.Assert.assertEquals
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

            assertTrue("First job should be canceled", job1.isCancelled)

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
                delay(Constants.Flow.DEBOUNCE_MS.milliseconds) // Simulate long processing
                processedValues.add(value)
            }

            runCurrent() // Start collection

            flow.emit(1)
            runCurrent()
            flow.emit(2)
            runCurrent()
            flow.emit(3)
            runCurrent()

            advanceTimeBy(Constants.Flow.DEBOUNCE_MS.milliseconds * 2)
            runCurrent()

            assertEquals(listOf(3), processedValues)
            assertEquals(3, startCount.get())
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
        runTest(timeout = Constants.Flow.DEFAULT_READ_TIMEOUT_MS.milliseconds * 2) {
            val scope = FlowObservationScope<Int>(backgroundScope, "test")
            val flow1 = MutableSharedFlow<Int>(replay = 1)
            val flow2 = MutableSharedFlow<Int>(replay = 1)
            val collector1Blocked = CompletableDeferred<Unit>()
            val collector1CanFinish = CompletableDeferred<Unit>()
            val collector2Values = mutableListOf<Int>()

            scope.observe(flow1) {
                collector1Blocked.complete(Unit)
                try {
                    delay(Constants.Flow.LONG_TIMEOUT_MS.milliseconds)
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

    @Test
    fun `second observe proceeds and launches new job if first collector ignores cancellation and times out`() =
        runTest(timeout = Constants.Flow.LONG_TIMEOUT_MS.milliseconds * 2) {
            val scope = FlowObservationScope<Int>(backgroundScope, "test")
            val flow1 = MutableSharedFlow<Int>(replay = 1)
            val flow2 = MutableSharedFlow<Int>(replay = 1)

            flow1.emit(1)
            scope.observe(flow1) {
                withContext(NonCancellable) {
                    delay(Constants.Flow.LONG_TIMEOUT_MS.milliseconds)
                }
            }
            runCurrent()

            flow2.emit(20)
            var newJob: Job? = null
            val secondObserveLaunch =
                launch {
                    newJob = scope.observe(flow2) { }
                }

            advanceTimeBy(Constants.Flow.NON_CANCELLABLE_CLEANUP_TIMEOUT_MS.milliseconds + 500.milliseconds)
            runCurrent()
            secondObserveLaunch.join()

            assertNotNull("New job must be launched after timeout even if previous stuck", newJob)
        }

    @Test
    fun `FlowObservationScope does not hold strong reference to collector after job cancellation`() =
        runTest {
            val scope = FlowObservationScope<Int>(backgroundScope, "test")
            val flow = MutableSharedFlow<Int>()

            class CollectorHolder {
                var counter = 0

                fun collect() {
                    counter++
                }
            }

            var holder: CollectorHolder? = CollectorHolder()
            val weakRef = java.lang.ref.WeakReference(holder)

            val job =
                scope.observe(flow) {
                    holder?.collect()
                }
            runCurrent()

            job.cancel()
            runCurrent()

            holder = null
            System.gc()

            assertNull("Collector instance should be garbage collected", weakRef.get())
        }

    @Test
    fun `ObservationMode COMPLETE processes all emissions without dropping intermediate values`() =
        runTest {
            val scope = FlowObservationScope<Int>(backgroundScope, "test", ObservationMode.COMPLETE)
            val flow = MutableSharedFlow<Int>(replay = 10)
            val processedValues = mutableListOf<Int>()

            scope.observe(flow) { value ->
                processedValues.add(value)
            }
            runCurrent()

            flow.emit(1)
            flow.emit(2)
            flow.emit(3)
            runCurrent()

            assertEquals(listOf(1, 2, 3), processedValues)
        }
}
