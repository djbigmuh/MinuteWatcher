package com.example.minutewatcher

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestMinuteWatcherApp::class)
class BootReceiverTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        
        // Use a configuration that doesn't fail on missing Hilt worker factory
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ListenableWorker? = null // Let it use default or fail gracefully
            })
            .build()
            
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
    }

    @Test
    fun testOnReceiveEnqueuesWork() = runBlocking {
        val receiver = BootReceiver()
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)

        receiver.onReceive(context, intent)
        
        // Wait for coroutines to finish enqueuing
        ShadowLooper.idleMainLooper()
        
        var workInfos: List<WorkInfo> = emptyList()
        for (i in 1..20) {
            workInfos = workManager.getWorkInfosForUniqueWork("BOOT_SETUP").get(1, TimeUnit.SECONDS)
            if (workInfos.isNotEmpty()) break
            delay(50)
        }
        
        assertTrue("Work should be enqueued", workInfos.isNotEmpty())
        // In some environments it might be ENQUEUED or FAILED (if it ran and failed), 
        // but we just check it exists.
    }
}
