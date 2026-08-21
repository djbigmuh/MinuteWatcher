package com.example.minutewatcher.benchmark

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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(application = TestMinuteWatcherApp::class)
class BenchmarkReceiverTest {
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
    fun `onReceive enqueues BenchmarkPopulateWorker`() =
        runTest {
            val receiver = BenchmarkReceiver()
            val simCount = 2
            val intent =
                Intent(BenchmarkReceiver.ACTION_POPULATE_DATA)
                    .apply {
                        putExtra(BenchmarkReceiver.EXTRA_SIM_COUNT, simCount)
                    }

            receiver.onReceive(context, intent)

            ShadowLooper.idleMainLooper()

            val workInfos =
                workManager
                    .getWorkInfosForUniqueWork("benchmark_populate_data")
                    .get()

            assertTrue("Work should be enqueued", workInfos.isNotEmpty())
        }

    @Test
    fun `onReceive ignores unknown action`() =
        runTest {
            val receiver = BenchmarkReceiver()
            val intent = Intent("UNKNOWN_ACTION")

            receiver.onReceive(context, intent)

            ShadowLooper.idleMainLooper()

            val workInfos =
                workManager
                    .getWorkInfosForUniqueWork("benchmark_populate_data")
                    .get()

            assertTrue("Work should not be enqueued", workInfos.isEmpty())
        }
}
