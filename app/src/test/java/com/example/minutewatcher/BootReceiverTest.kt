package com.example.minutewatcher

import android.content.BroadcastReceiver
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
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

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
            val mockPendingResult = mock(BroadcastReceiver.PendingResult::class.java)
            val receiver =
                object : BootReceiver() {
                    override fun acquirePendingResult() = mockPendingResult
                }

            val intent = Intent(Intent.ACTION_BOOT_COMPLETED)

            receiver.onReceive(context, intent)

            // Wait for coroutines to finish
            advanceUntilIdle()
            ShadowLooper.idleMainLooper()

            var workInfos: List<WorkInfo> = emptyList()
            repeat(20) {
                workInfos =
                    workManager
                        .getWorkInfosForUniqueWork("INITIAL_SETUP")
                        .get(1, TimeUnit.SECONDS)
                if (workInfos.isNotEmpty()) return@repeat
                delay(50.milliseconds)
            }

            assertTrue("Work INITIAL_SETUP should be enqueued", workInfos.isNotEmpty())
            verify(mockPendingResult, org.mockito.Mockito.times(1)).finish()
        }

    @Test
    fun `onReceive calls pendingResult finish after work completes`() =
        runTest {
            val mockPendingResult = mock(BroadcastReceiver.PendingResult::class.java)
            val receiver =
                object : BootReceiver() {
                    override fun acquirePendingResult() = mockPendingResult
                }

            val intent = Intent(Intent.ACTION_BOOT_COMPLETED)

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
                object : BootReceiver() {
                    override fun acquirePendingResult() = mockPendingResult
                }

            // Mock context to throw exception when applicationContext is accessed inside the coroutine.
            // Note: In BootReceiver, appContext is retrieved BEFORE the coroutine.
            // So we need to mock something used INSIDE the coroutine.
            val failingContext = mock(Context::class.java)
            val mockAppContext = mock(Context::class.java)
            whenever(failingContext.applicationContext).thenReturn(mockAppContext)

            // WorkManager.getInstance(mockAppContext) will throw if WorkManager is not initialized for this context
            // and we didn't use WorkManagerTestInitHelper for it.
            // Or we can mock mockAppContext.getSystemService(...) to throw.
            whenever(mockAppContext.getSystemService(anyString())).thenThrow(RuntimeException("Intentional failure"))

            val intent = Intent(Intent.ACTION_BOOT_COMPLETED)

            receiver.onReceive(failingContext, intent)

            advanceUntilIdle()
            ShadowLooper.idleMainLooper()

            verify(mockPendingResult, org.mockito.Mockito.times(1)).finish()
        }
}
