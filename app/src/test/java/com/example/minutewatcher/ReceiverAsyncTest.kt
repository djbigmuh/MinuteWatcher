package com.example.minutewatcher

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
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.milliseconds

@RunWith(RobolectricTestRunner::class)
@Config(application = TestMinuteWatcherApp::class)
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
                throw CancellationException("Cancelled")
            }

            advanceUntilIdle()

            verify(mockPendingResult, times(1)).finish()
        }
}
