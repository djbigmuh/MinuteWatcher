package com.example.minutewatcher.benchmark

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.minutewatcher.TestMinuteWatcherApp
import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(application = TestMinuteWatcherApp::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BenchmarkReceiverTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val config =
            Configuration
                .Builder()
                .setWorkerFactory(
                    object : WorkerFactory() {
                        override fun createWorker(
                            appContext: Context,
                            workerClassName: String,
                            workerParameters: WorkerParameters,
                        ): ListenableWorker? = null
                    },
                ).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
    }

    @Test
    fun `onReceive enqueues BenchmarkPopulateWorker and calls finish`() =
        runTest {
            val mockPendingResult = mock(BroadcastReceiver.PendingResult::class.java)
            val receiver =
                object : BenchmarkReceiver() {
                    override fun acquirePendingResult() = mockPendingResult

                    override fun asyncDispatcher(): CoroutineDispatcher = mainDispatcherRule.testDispatcher
                }

            val simCount = 2
            val intent =
                Intent(BenchmarkReceiver.ACTION_POPULATE_DATA).apply {
                    putExtra(BenchmarkReceiver.EXTRA_SIM_COUNT, simCount)
                }

            receiver.onReceive(context, intent)

            advanceUntilIdle()
            ShadowLooper.idleMainLooper()

            val workInfos =
                workManager
                    .getWorkInfosForUniqueWork("benchmark_populate_data")
                    .get()

            assertTrue("Work should be enqueued", workInfos.isNotEmpty())
            verify(mockPendingResult, times(1)).finish()
        }

    @Test
    fun `onReceive ignores unknown action without acquiring pending result`() =
        runTest {
            val mockPendingResult = mock(BroadcastReceiver.PendingResult::class.java)
            val receiver =
                object : BenchmarkReceiver() {
                    override fun acquirePendingResult() = mockPendingResult

                    override fun asyncDispatcher(): CoroutineDispatcher = mainDispatcherRule.testDispatcher
                }

            val intent = Intent("UNKNOWN_ACTION")

            receiver.onReceive(context, intent)

            advanceUntilIdle()
            ShadowLooper.idleMainLooper()

            val workInfos =
                workManager
                    .getWorkInfosForUniqueWork("benchmark_populate_data")
                    .get()

            assertTrue("Work should not be enqueued", workInfos.isEmpty())
            verify(mockPendingResult, never()).finish()
        }

    @Test
    fun `onReceive calls finish even when enqueue throws exception`() =
        runTest {
            val mockPendingResult = mock(BroadcastReceiver.PendingResult::class.java)
            val receiver =
                object : BenchmarkReceiver() {
                    override fun acquirePendingResult() = mockPendingResult

                    override fun asyncDispatcher(): CoroutineDispatcher = mainDispatcherRule.testDispatcher
                }

            val mockContext = mock(Context::class.java)
            whenever(mockContext.applicationContext).thenReturn(mockContext)
            whenever(mockContext.getSystemService(any())).thenThrow(RuntimeException("WorkManager error"))

            val intent = Intent(BenchmarkReceiver.ACTION_POPULATE_DATA)

            receiver.onReceive(mockContext, intent)

            advanceUntilIdle()
            ShadowLooper.idleMainLooper()

            verify(mockPendingResult, times(1)).finish()
        }
}
