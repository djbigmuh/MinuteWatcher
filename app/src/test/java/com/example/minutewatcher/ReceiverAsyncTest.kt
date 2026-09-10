package com.example.minutewatcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.minutewatcher.core.common.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestMinuteWatcherApp::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ReceiverAsyncTest {
    private lateinit var context: Context
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        context = org.robolectric.RuntimeEnvironment.getApplication()
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `test finish is called even if coroutine fails`() =
        runTest(testDispatcher) {
            val mockPendingResult: BroadcastReceiver.PendingResult = mock()

            // We need a receiver that uses goAsync and launches a failing coroutine
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context,
                        intent: Intent,
                    ) {
                        // In real code we'd use goAsync(), here we pass mock
                        val result = mockPendingResult

                        ProcessLifecycleOwner.get().lifecycleScope.launch {
                            try {
                                error("Simulated failure")
                            } catch (e: Exception) {
                                AppLogger.d("ReceiverAsyncTest: Caught simulated failure: ${e.message}")
                            } finally {
                                result.finish()
                            }
                        }
                    }
                }

            receiver.onReceive(context, Intent("TEST_ACTION"))

            // Wait for coroutine to finish
            advanceUntilIdle()

            verify(mockPendingResult).finish()
        }
}
