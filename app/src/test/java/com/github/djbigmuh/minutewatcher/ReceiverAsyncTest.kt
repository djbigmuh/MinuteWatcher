package com.github.djbigmuh.minutewatcher

import android.content.BroadcastReceiver
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

@RunWith(RobolectricTestRunner::class)
@Config(application = MinuteWatcherTestApp::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ReceiverAsyncTest {
    private lateinit var context: Context
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `goAsyncWithScope calls finish once when block completes successfully`() =
        runTest(testDispatcher) {
            val mockPendingResult: BroadcastReceiver.PendingResult = mock()

            goAsyncWithScope(context, mockPendingResult, dispatcher = testDispatcher) {
                // Success block
            }

            advanceUntilIdle()

            verify(mockPendingResult, times(1)).finish()
        }

    @Test
    fun `goAsyncWithScope calls finish once when block throws RuntimeException`() =
        runTest(testDispatcher) {
            val mockPendingResult: BroadcastReceiver.PendingResult = mock()

            goAsyncWithScope(context, mockPendingResult, dispatcher = testDispatcher) {
                throw IllegalStateException("Simulated failure")
            }

            advanceUntilIdle()

            verify(mockPendingResult, times(1)).finish()
        }

    @Test
    fun `goAsyncWithScope calls finish once when block times out`() =
        runTest(testDispatcher) {
            val mockPendingResult: BroadcastReceiver.PendingResult = mock()

            goAsyncWithScope(context, mockPendingResult, dispatcher = testDispatcher) {
                delay(10_000.milliseconds)
            }

            advanceUntilIdle()

            verify(mockPendingResult, times(1)).finish()
        }

    @Test
    fun `goAsyncWithScope calls finish once when block throws CancellationException`() =
        runTest(testDispatcher) {
            val mockPendingResult: BroadcastReceiver.PendingResult = mock()

            goAsyncWithScope(context, mockPendingResult, dispatcher = testDispatcher) {
                throw CancellationException("Canceled")
            }

            advanceUntilIdle()

            verify(mockPendingResult, times(1)).finish()
        }

    @Test
    fun `goAsyncWithScope executes block when dispatcher is null and entry point unavailable`() =
        runTest(testDispatcher) {
            val mockPendingResult: BroadcastReceiver.PendingResult = mock()
            val latch = CountDownLatch(1)

            withTimeout(5_000.milliseconds) {
                goAsyncWithScope(context, mockPendingResult, dispatcher = null) {
                    latch.countDown()
                }
                val executed = latch.await(2, TimeUnit.SECONDS)
                assertTrue(
                    "Async block should execute on fallback dispatcher when entry point is unavailable",
                    executed,
                )
                verify(mockPendingResult, Mockito.timeout(2_000).times(1)).finish()
            }
        }

    @Test
    fun `after block completion, created scope and supervisor job are canceled`() =
        runTest(testDispatcher) {
            val mockPendingResult: BroadcastReceiver.PendingResult = mock()

            val job =
                goAsyncWithScope(context, mockPendingResult, dispatcher = testDispatcher) {
                    // Success block
                }

            advanceUntilIdle()

            assertTrue("Job should be completed/canceled after completion", job.isCompleted)
            verify(mockPendingResult, times(1)).finish()
        }

    @Test
    fun `when EntryPoint is unavailable, fallback dispatcher is used and finish is called`() =
        runTest(testDispatcher) {
            val mockPendingResult: BroadcastReceiver.PendingResult = mock()
            val nonHiltContext: Context = mock()
            val mockAppContext: Context = mock()
            whenever(nonHiltContext.applicationContext).thenReturn(mockAppContext)

            val latch = CountDownLatch(1)
            goAsyncWithScope(nonHiltContext, mockPendingResult, dispatcher = null) {
                latch.countDown()
            }

            val executed = latch.await(3, TimeUnit.SECONDS)

            assertTrue("Block should execute on fallback dispatcher", executed)
            verify(mockPendingResult, Mockito.timeout(3_000).times(1)).finish()
        }
}
