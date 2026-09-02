package com.example.minutewatcher

import android.content.BroadcastReceiver
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
import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.data.CallEventWorker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Tests verify that goAsync() contract is respected:
 * - finish() is called exactly once
 * - finish() is called even if work throws exception
 * - finish() is called after all async work completes
 *
 * This is strict Android lifecycle compliance, not optional.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestMinuteWatcherApp::class, sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class UsageMonitorReceiverTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()

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
        // MainDispatcherRule handles resetMain()
    }

    @Test
    fun testOnReceiveEnqueuesCallEventWork() =
        runTest {
            val mockPendingResult = mock(BroadcastReceiver.PendingResult::class.java)
            val receiver =
                object : UsageMonitorReceiver() {
                    override fun acquirePendingResult() = mockPendingResult
                }

            val intent =
                Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
                    .apply {
                        putExtra(TelephonyManager.EXTRA_STATE, TelephonyManager.EXTRA_STATE_OFFHOOK)
                        putExtra("subscription", 1)
                    }

            receiver.onReceive(context, intent)

            advanceUntilIdle()
            ShadowLooper.idleMainLooper()

            val workInfos =
                workManager
                    .getWorkInfosForUniqueWork("CALL_EVENT_1")
                    .get()

            assertTrue("Work CALL_EVENT_1 should be enqueued", workInfos.isNotEmpty())
            verify(mockPendingResult, org.mockito.Mockito.times(1)).finish()
        }

    @Test
    fun testOnReceiveUnknownSubIdEnqueuesWork() =
        runTest {
            val mockPendingResult = mock(BroadcastReceiver.PendingResult::class.java)
            val receiver =
                object : UsageMonitorReceiver() {
                    override fun acquirePendingResult() = mockPendingResult
                }

            val intent =
                Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
                    .apply {
                        putExtra(TelephonyManager.EXTRA_STATE, TelephonyManager.EXTRA_STATE_IDLE)
                    }

            receiver.onReceive(context, intent)

            advanceUntilIdle()
            ShadowLooper.idleMainLooper()

            val workInfos =
                workManager
                    .getWorkInfosForUniqueWork("CALL_EVENT_UNKNOWN")
                    .get()

            assertTrue("Work CALL_EVENT_UNKNOWN should be enqueued", workInfos.isNotEmpty())
            verify(mockPendingResult, org.mockito.Mockito.times(1)).finish()
        }

    @Test
    fun testOnReceiveTelephonyActionEnqueuesWork() =
        runTest {
            val mockPendingResult = mock(BroadcastReceiver.PendingResult::class.java)
            val receiver =
                object : UsageMonitorReceiver() {
                    override fun acquirePendingResult() = mockPendingResult
                }

            // Test the alternative action string
            val intent =
                Intent("android.telephony.action.PHONE_STATE_CHANGED")
                    .apply {
                        putExtra(TelephonyManager.EXTRA_STATE, TelephonyManager.EXTRA_STATE_OFFHOOK)
                        putExtra("subscription", 2)
                    }

            receiver.onReceive(context, intent)

            advanceUntilIdle()
            ShadowLooper.idleMainLooper()

            val workInfos =
                workManager
                    .getWorkInfosForUniqueWork("CALL_EVENT_2")
                    .get()

            assertTrue("Work CALL_EVENT_2 should be enqueued for telephony action", workInfos.isNotEmpty())
            verify(mockPendingResult, org.mockito.Mockito.times(1)).finish()
        }

    @Test
    fun testOnReceiveNullStateDoesNotEnqueueWork() =
        runTest {
            val mockPendingResult = mock(BroadcastReceiver.PendingResult::class.java)
            val receiver =
                object : UsageMonitorReceiver() {
                    override fun acquirePendingResult() = mockPendingResult
                }

            val intent = Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            // No EXTRA_STATE put

            receiver.onReceive(context, intent)

            advanceUntilIdle()
            ShadowLooper.idleMainLooper()

            // Check that no unique work was enqueued. Since we don't know the name if it failed,
            // we check by tag (which is always added by default for the worker class)
            val workInfos =
                workManager
                    .getWorkInfosByTag(CallEventWorker::class.java.name)
                    .get()

            assertTrue("No work should be enqueued when state is null", workInfos.isEmpty())
            verify(mockPendingResult, org.mockito.Mockito.times(1)).finish()
        }

    @Test
    fun `onReceive calls pendingResult finish after work completes`() =
        runTest {
            val mockPendingResult = mock(BroadcastReceiver.PendingResult::class.java)
            val receiver =
                object : UsageMonitorReceiver() {
                    override fun acquirePendingResult() = mockPendingResult
                }

            val intent =
                Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
                    .apply {
                        putExtra(TelephonyManager.EXTRA_STATE, TelephonyManager.EXTRA_STATE_OFFHOOK)
                    }

            receiver.onReceive(context, intent)

            advanceUntilIdle()
            ShadowLooper.idleMainLooper()

            verify(mockPendingResult, org.mockito.Mockito.times(1)).finish()
        }

    @Test
    fun `onReceive calls pendingResult finish even on exception`() =
        runTest {
            val mockPendingResult = mock(BroadcastReceiver.PendingResult::class.java)
            val receiver =
                object : UsageMonitorReceiver() {
                    override fun acquirePendingResult() = mockPendingResult
                }

            val failingContext = mock(Context::class.java)
            val mockAppContext = mock(Context::class.java)
            whenever(failingContext.applicationContext).thenReturn(mockAppContext)
            whenever(mockAppContext.getSystemService(anyString())).thenThrow(RuntimeException("Intentional failure"))

            val intent =
                Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
                    .apply {
                        putExtra(TelephonyManager.EXTRA_STATE, TelephonyManager.EXTRA_STATE_OFFHOOK)
                    }

            receiver.onReceive(failingContext, intent)

            advanceUntilIdle()
            ShadowLooper.idleMainLooper()

            verify(mockPendingResult, org.mockito.Mockito.times(1)).finish()
        }
}
