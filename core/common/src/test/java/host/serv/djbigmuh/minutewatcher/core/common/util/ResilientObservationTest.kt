package host.serv.djbigmuh.minutewatcher.core.common.util

import host.serv.djbigmuh.minutewatcher.core.model.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ResilientObservationTest {
    @Test
    fun `source values pass through unmodified`() =
        runTest {
            val source = flowOf(1, 2, 3)
            val result =
                source
                    .observeWithRetry(
                        label = "test",
                        logThreshold = 5,
                        onPersistentFailure = {},
                        onRecovered = {},
                        onSourceCompleted = {},
                    ).toList()
            assertEquals(listOf(1, 2, 3), result)
        }

    @Test
    fun `restarts collection on error with exponential delay`() =
        runTest {
            var attempts = 0
            val source =
                flow {
                    attempts++
                    if (attempts == 1) {
                        throw IOException("First failure")
                    }
                    emit("success")
                }

            val emitted = mutableListOf<String>()
            val job =
                launch {
                    source
                        .observeWithRetry(
                            label = "test",
                            logThreshold = 5,
                            onPersistentFailure = {},
                            onRecovered = {},
                            onSourceCompleted = {},
                        ).collect { emitted.add(it) }
                }

            runCurrent()
            assertEquals(0, emitted.size)

            advanceTimeBy(Constants.Flow.RETRY_DELAY_MS.milliseconds)
            runCurrent()

            assertEquals(listOf("success"), emitted)
            job.cancel()
        }

    @Test
    fun `retry delay remains bounded at maximum delay during long persistent failures`() =
        runTest {
            var attemptCount = 0
            val source =
                flow<Int> {
                    attemptCount++
                    throw IOException("Persistent error")
                }

            val job =
                launch {
                    source
                        .observeWithRetry(
                            label = "test",
                            logThreshold = 5,
                            onPersistentFailure = {},
                            onRecovered = {},
                            onSourceCompleted = {},
                        ).collect {}
                }
            runCurrent()

            var stepCount = 0
            while ((attemptCount < 31) && (stepCount < 1000)) {
                advanceTimeBy(Constants.Flow.RETRY_DELAY_MS.milliseconds)
                runCurrent()
                stepCount++
            }
            assertEquals(31, attemptCount)

            val attemptsBeforeWindow = attemptCount
            advanceTimeBy(61.seconds)
            runCurrent()

            val newAttempts = attemptCount - attemptsBeforeWindow
            assertEquals(2, newAttempts)
            job.cancel()
        }

    @Test
    fun `onRecovered is called once on first emission after errors and resets error count`() =
        runTest {
            var attempts = 0
            var recoveredCalls = 0
            val source =
                flow {
                    attempts++
                    if (attempts <= 2) {
                        throw IOException("Failure $attempts")
                    }
                    emit("value_$attempts")
                }

            val emitted = mutableListOf<String>()
            val job =
                launch {
                    source
                        .observeWithRetry(
                            label = "test",
                            logThreshold = 5,
                            onPersistentFailure = {},
                            onRecovered = { recoveredCalls++ },
                            onSourceCompleted = {},
                        ).collect { emitted.add(it) }
                }

            runCurrent()
            advanceTimeBy(Constants.Flow.RETRY_DELAY_MS.milliseconds)
            runCurrent()
            advanceTimeBy(Constants.Flow.RETRY_DELAY_MS.milliseconds * 2)
            runCurrent()

            assertEquals(1, recoveredCalls)
            assertEquals(listOf("value_3"), emitted)
            job.cancel()
        }

    @Test
    fun `onPersistentFailure is invoked on each error starting from logThreshold`() =
        runTest {
            var attempts = 0
            val failures = mutableListOf<Throwable>()
            val source =
                flow<Int> {
                    attempts++
                    throw IOException("Error $attempts")
                }

            val job =
                launch {
                    source
                        .observeWithRetry(
                            label = "test",
                            logThreshold = 3,
                            onPersistentFailure = { failures.add(it) },
                            onRecovered = {},
                            onSourceCompleted = {},
                        ).collect {}
                }

            runCurrent()
            advanceTimeBy(Constants.Flow.RETRY_DELAY_MS.milliseconds)
            runCurrent()
            advanceTimeBy(Constants.Flow.RETRY_DELAY_MS.milliseconds * 2)
            runCurrent()
            advanceTimeBy(Constants.Flow.RETRY_DELAY_MS.milliseconds * 4)
            runCurrent()
            advanceTimeBy(Constants.Flow.RETRY_DELAY_MS.milliseconds * 8)
            runCurrent()

            assertEquals(3, failures.size)
            job.cancel()
        }

    @Test
    fun `source completion invokes onSourceCompleted and completes flow`() =
        runTest {
            var completedCalls = 0
            val source = flowOf(1, 2)

            val result =
                source
                    .observeWithRetry(
                        label = "test",
                        logThreshold = 5,
                        onPersistentFailure = {},
                        onRecovered = {},
                        onSourceCompleted = { completedCalls++ },
                    ).toList()

            assertEquals(listOf(1, 2), result)
            assertEquals(1, completedCalls)
        }

    @Test
    fun `CancellationException is rethrown without triggering failure or completion callbacks`() =
        runTest {
            var failureCalled = false
            var completedCalled = false
            val source =
                flow<Int> {
                    throw CancellationException("Canceled")
                }

            try {
                source
                    .observeWithRetry(
                        label = "test",
                        logThreshold = 1,
                        onPersistentFailure = { failureCalled = true },
                        onRecovered = {},
                        onSourceCompleted = { completedCalled = true },
                    ).collect {}
            } catch (_: CancellationException) {
                // Expected
            }

            assertFalse("onPersistentFailure should not be called on CancellationException", failureCalled)
            assertFalse("onSourceCompleted should not be called on CancellationException", completedCalled)
        }
}
