package com.example.minutewatcher

import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
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
class UsageMonitorReceiverTest {
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
    fun testOnReceiveEnqueuesCallEventWork() =
        runTest {
            val receiver = UsageMonitorReceiver()
            val intent =
                Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED).apply {
                    putExtra(TelephonyManager.EXTRA_STATE, TelephonyManager.EXTRA_STATE_OFFHOOK)
                    putExtra("subscription", 1)
                }

            receiver.onReceive(context, intent)

            ShadowLooper.idleMainLooper()

            val workInfos = workManager.getWorkInfosForUniqueWork("CALL_EVENT_1").get()

            assertTrue("Work CALL_EVENT_1 should be enqueued", workInfos.isNotEmpty())
        }

    @Test
    fun testOnReceiveUnknownSubIdEnqueuesWork() =
        runTest {
            val receiver = UsageMonitorReceiver()
            val intent =
                Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED).apply {
                    putExtra(TelephonyManager.EXTRA_STATE, TelephonyManager.EXTRA_STATE_IDLE)
                }

            receiver.onReceive(context, intent)

            ShadowLooper.idleMainLooper()

            val workInfos = workManager.getWorkInfosForUniqueWork("CALL_EVENT_UNKNOWN").get()

            assertTrue("Work CALL_EVENT_UNKNOWN should be enqueued", workInfos.isNotEmpty())
        }
}


