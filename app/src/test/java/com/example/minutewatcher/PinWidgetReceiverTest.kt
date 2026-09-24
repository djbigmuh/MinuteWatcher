package com.example.minutewatcher

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(application = TestMinuteWatcherApp::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PinWidgetReceiverTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var context: Context
    private lateinit var mockPendingResult: BroadcastReceiver.PendingResult

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        mockPendingResult = mock(BroadcastReceiver.PendingResult::class.java)
    }

    @Test
    fun `onReceive with invalid appWidgetId exits early without acquiring pendingResult or updating widget`() =
        runTest {
            var acquireCount = 0
            var updateCount = 0

            val receiver =
                object : PinWidgetReceiver() {
                    override fun acquirePendingResult(): PendingResult {
                        acquireCount++
                        return mockPendingResult
                    }

                    override fun asyncDispatcher(): CoroutineDispatcher = mainDispatcherRule.testDispatcher

                    override suspend fun updateWidget(
                        appContext: Context,
                        appWidgetId: Int,
                    ) {
                        updateCount++
                    }
                }

            val intent = Intent() // No EXTRA_APPWIDGET_ID extra
            receiver.onReceive(context, intent)

            advanceUntilIdle()
            ShadowLooper.idleMainLooper()

            assertEquals(0, acquireCount)
            assertEquals(0, updateCount)
            verify(mockPendingResult, never()).finish()
        }

    @Test
    fun `onReceive with valid appWidgetId calls updateWidget and finishes pendingResult once`() =
        runTest {
            var acquireCount = 0
            var updateCount = 0
            var capturedWidgetId = -1

            val receiver =
                object : PinWidgetReceiver() {
                    override fun acquirePendingResult(): PendingResult {
                        acquireCount++
                        return mockPendingResult
                    }

                    override fun asyncDispatcher(): CoroutineDispatcher = mainDispatcherRule.testDispatcher

                    override suspend fun updateWidget(
                        appContext: Context,
                        appWidgetId: Int,
                    ) {
                        updateCount++
                        capturedWidgetId = appWidgetId
                    }
                }

            val intent =
                Intent().apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 42)
                }

            receiver.onReceive(context, intent)

            advanceUntilIdle()
            ShadowLooper.idleMainLooper()

            assertEquals(1, acquireCount)
            assertEquals(1, updateCount)
            assertEquals(42, capturedWidgetId)
            verify(mockPendingResult, Mockito.times(1)).finish()
        }

    @Test
    fun `onReceive finishes pendingResult once when updateWidget throws exception`() =
        runTest {
            var acquireCount = 0
            var updateCount = 0

            val receiver =
                object : PinWidgetReceiver() {
                    override fun acquirePendingResult(): PendingResult {
                        acquireCount++
                        return mockPendingResult
                    }

                    override fun asyncDispatcher(): CoroutineDispatcher = mainDispatcherRule.testDispatcher

                    override suspend fun updateWidget(
                        appContext: Context,
                        appWidgetId: Int,
                    ) {
                        updateCount++
                        throw IllegalStateException("Simulated widget update failure")
                    }
                }

            val intent =
                Intent().apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 100)
                }

            receiver.onReceive(context, intent)

            advanceUntilIdle()
            ShadowLooper.idleMainLooper()

            assertEquals(1, acquireCount)
            assertEquals(1, updateCount)
            verify(mockPendingResult, Mockito.times(1)).finish()
        }
}
