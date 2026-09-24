package com.example.minutewatcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
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
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * Tests verify that goAsync() contract is respected:
 * - finish() is called exactly once
 * - finish() is called even if work throws exception
 * - finish() is called after all async work completes
 *
 * This is strict Android lifecycle compliance, not optional.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestMinuteWatcherApp::class)
@OptIn(ExperimentalCoroutinesApi::class)
class UsageMonitorReceiverTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()

        val lifecycle = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        val config =
            Configuration
                .Builder()
                .setWorkerFactory(
                    object : WorkerFactory() {
                        override fun createWorker(
                            appContext: Context,
                            workerClassName: String,
                            workerParameters: WorkerParameters,
                        ): ListenableWorker = TestListenableWorker(appContext, workerParameters)
                    },
                ).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
    }

    private class TestListenableWorker(
        context: Context,
        workerParams: WorkerParameters,
    ) : ListenableWorker(context, workerParams) {
        override fun startWork(): ListenableFuture<Result> =
            SettableFuture.create<Result>().apply { set(Result.success()) }
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
                    .getWorkInfosForUniqueWork("CALL_EVENT_CHAIN")
                    .get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            assertTrue("Work CALL_EVENT_CHAIN should be enqueued", workInfos.isNotEmpty())
            verify(mockPendingResult, Mockito.times(1)).finish()
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
                    .getWorkInfosForUniqueWork("CALL_EVENT_CHAIN")
                    .get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            assertTrue("Work CALL_EVENT_CHAIN should be enqueued", workInfos.isNotEmpty())
            verify(mockPendingResult, Mockito.times(1)).finish()
        }

    @Test
    fun testOnReceiveRingingStateDoesNotEnqueueWork() =
        runTest {
            val mockPendingResult = mock(BroadcastReceiver.PendingResult::class.java)
            val receiver =
                object : UsageMonitorReceiver() {
                    override fun acquirePendingResult() = mockPendingResult
                }

            val intent =
                Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
                    .apply {
                        putExtra(TelephonyManager.EXTRA_STATE, TelephonyManager.EXTRA_STATE_RINGING)
                    }

            receiver.onReceive(context, intent)

            advanceUntilIdle()
            ShadowLooper.idleMainLooper()

            val workInfos =
                workManager
                    .getWorkInfosByTag(CallEventWorker::class.java.name)
                    .get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            assertTrue("No work should be enqueued when state is RINGING", workInfos.isEmpty())
            verify(mockPendingResult, Mockito.times(1)).finish()
        }

    @Test
    fun testOnReceiveMaintainsGlobalOrder() =
        runTest {
            val mockPendingResult1 = mock(BroadcastReceiver.PendingResult::class.java)
            val mockPendingResult2 = mock(BroadcastReceiver.PendingResult::class.java)

            val receiver =
                object : UsageMonitorReceiver() {
                    private var callCount = 0

                    override fun acquirePendingResult(): PendingResult {
                        callCount++
                        return if (callCount == 1) mockPendingResult1 else mockPendingResult2
                    }
                }

            val intentOffhook =
                Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
                    .apply {
                        putExtra(TelephonyManager.EXTRA_STATE, TelephonyManager.EXTRA_STATE_OFFHOOK)
                        putExtra("subscription", 1)
                    }

            val intentIdle =
                Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
                    .apply {
                        putExtra(TelephonyManager.EXTRA_STATE, TelephonyManager.EXTRA_STATE_IDLE)
                    }

            receiver.onReceive(context, intentOffhook)
            receiver.onReceive(context, intentIdle)

            advanceUntilIdle()
            ShadowLooper.idleMainLooper()

            val workInfos =
                workManager
                    .getWorkInfosForUniqueWork("CALL_EVENT_CHAIN")
                    .get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            Assert.assertEquals("Should have exactly 2 workers enqueued in the unique chain", 2, workInfos.size)
            verify(mockPendingResult1, Mockito.times(1)).finish()
            verify(mockPendingResult2, Mockito.times(1)).finish()
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
                    .get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            assertTrue("No work should be enqueued when state is null", workInfos.isEmpty())
            verify(mockPendingResult, Mockito.times(1)).finish()
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

            verify(mockPendingResult, Mockito.times(1)).finish()
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

            verify(mockPendingResult, Mockito.times(1)).finish()
        }

    private companion object {
        private const val WAIT_TIMEOUT_SECONDS = 10L
    }
}
