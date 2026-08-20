package com.example.minutewatcher

import android.content.Context
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

@RunWith(RobolectricTestRunner::class)
@Config(application = TestMinuteWatcherApp::class, sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class BootReceiverTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        Dispatchers.setMain(testDispatcher)

        val lifecycle = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        // Use a configuration that doesn't fail on missing Hilt worker factory
        val config =
            Configuration
                .Builder()
                .setMinimumLoggingLevel(android.util.Log.DEBUG)
                .setWorkerFactory(
                    object : WorkerFactory() {
                        override fun createWorker(
                            appContext: Context,
                            workerClassName: String,
                            workerParameters: WorkerParameters,
                        ): ListenableWorker? = null // Let it use default or fail gracefully
                    },
                ).build()

        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testOnReceiveEnqueuesWork() =
        runTest {
            val receiver = BootReceiver()
            val intent = Intent(Intent.ACTION_BOOT_COMPLETED)

            receiver.onReceive(context, intent)

            // Wait for coroutines in lifecycleScope to finish
            advanceUntilIdle()
            ShadowLooper.idleMainLooper()

            var workInfos: List<WorkInfo> = emptyList()
            repeat(20) {
                workInfos = workManager.getWorkInfosForUniqueWork("INITIAL_SETUP").get(1, TimeUnit.SECONDS)
                if (workInfos.isNotEmpty()) return@repeat
                delay(50.milliseconds)
            }

            assertTrue("Work INITIAL_SETUP should be enqueued", workInfos.isNotEmpty())
        }
}
