package com.example.minutewatcher

import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.minutewatcher.core.data.CallEventWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

@RunWith(RobolectricTestRunner::class)
@Config(application = TestMinuteWatcherApp::class, sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class UsageMonitorReceiverTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        Dispatchers.setMain(testDispatcher)

        val lifecycle = ProcessLifecycleOwner.get().lifecycle as androidx.lifecycle.LifecycleRegistry
        lifecycle.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_CREATE)
        lifecycle.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
        lifecycle.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)
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

    @After
    fun tearDown() {
        Dispatchers.resetMain()
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

            advanceUntilIdle()
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

            advanceUntilIdle()
            ShadowLooper.idleMainLooper()

            val workInfos = workManager.getWorkInfosForUniqueWork("CALL_EVENT_UNKNOWN").get()

            assertTrue("Work CALL_EVENT_UNKNOWN should be enqueued", workInfos.isNotEmpty())
        }

    @Test
    fun testOnReceiveTelephonyActionEnqueuesWork() =
        runTest {
            val receiver = UsageMonitorReceiver()
            // Test the alternative action string
            val intent =
                Intent("android.telephony.action.PHONE_STATE_CHANGED").apply {
                    putExtra(TelephonyManager.EXTRA_STATE, TelephonyManager.EXTRA_STATE_OFFHOOK)
                    putExtra("subscription", 2)
                }

            receiver.onReceive(context, intent)

            advanceUntilIdle()
            ShadowLooper.idleMainLooper()

            val workInfos = workManager.getWorkInfosForUniqueWork("CALL_EVENT_2").get()

            assertTrue("Work CALL_EVENT_2 should be enqueued for telephony action", workInfos.isNotEmpty())
        }

    @Test
    fun testOnReceiveNullStateDoesNotEnqueueWork() =
        runTest {
            val receiver = UsageMonitorReceiver()
            val intent = Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            // No EXTRA_STATE put

            receiver.onReceive(context, intent)

            advanceUntilIdle()
            ShadowLooper.idleMainLooper()

            // Check that no unique work was enqueued. Since we don't know the name if it failed,
            // we check by tag (which is always added by default for the worker class)
            val workInfos = workManager.getWorkInfosByTag(CallEventWorker::class.java.name).get()

            assertTrue("No work should be enqueued when state is null", workInfos.isEmpty())
        }
}
