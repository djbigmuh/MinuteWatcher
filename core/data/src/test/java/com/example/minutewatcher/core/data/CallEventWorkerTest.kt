package com.example.minutewatcher.core.data

import android.content.Context
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class CallEventWorkerTest {
    private val callTracker: CallTracker = mock()
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `doWork calls onCallStarted for OFFHOOK with eventElapsedRealtime`() =
        runTest {
            val subId = 1
            val eventTime = 12345L
            val inputData =
                Data
                    .Builder()
                    .putString("state", TelephonyManager.EXTRA_STATE_OFFHOOK)
                    .putInt("subId", subId)
                    .putLong(CallEventWorker.EXTRA_EVENT_ELAPSED_REALTIME, eventTime)
                    .build()

            val worker =
                TestListenableWorkerBuilder<CallEventWorker>(context)
                    .setWorkerFactory(
                        object : androidx.work.WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: androidx.work.WorkerParameters,
                            ): ListenableWorker = CallEventWorker(appContext, workerParameters, callTracker)
                        },
                    ).setInputData(inputData)
                    .build()

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(callTracker).onCallStarted(subId, eventTime)
        }

    @Test
    fun `doWork calls onCallEnded for IDLE with eventElapsedRealtime`() =
        runTest {
            val subId = 1
            val eventTime = 67890L
            val inputData =
                Data
                    .Builder()
                    .putString("state", TelephonyManager.EXTRA_STATE_IDLE)
                    .putInt("subId", subId)
                    .putLong(CallEventWorker.EXTRA_EVENT_ELAPSED_REALTIME, eventTime)
                    .build()

            val worker =
                TestListenableWorkerBuilder<CallEventWorker>(context)
                    .setWorkerFactory(
                        object : androidx.work.WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: androidx.work.WorkerParameters,
                            ): ListenableWorker = CallEventWorker(appContext, workerParameters, callTracker)
                        },
                    ).setInputData(inputData)
                    .build()

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(callTracker).onCallEnded(subId, eventTime)
        }

    @Test
    fun `doWork returns success for unknown state`() =
        runTest {
            val inputData =
                Data
                    .Builder()
                    .putString("state", "UNKNOWN_STATE")
                    .build()

            val worker =
                TestListenableWorkerBuilder<CallEventWorker>(context)
                    .setWorkerFactory(
                        object : androidx.work.WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: androidx.work.WorkerParameters,
                            ): ListenableWorker = CallEventWorker(appContext, workerParameters, callTracker)
                        },
                    ).setInputData(inputData)
                    .build()

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verifyNoInteractions(callTracker)
        }
}
