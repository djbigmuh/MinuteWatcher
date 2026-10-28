package host.serv.djbigmuh.minutewatcher.core.data

import android.content.Context
import android.telephony.TelephonyManager
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import host.serv.djbigmuh.minutewatcher.core.common.util.initTestLogger
import host.serv.djbigmuh.minutewatcher.core.data.util.DataStoreWriteTimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CallEventWorkerTest {
    private val callTracker: CallTracker = mock()
    private val context: Context = mock()

    @Before
    fun setup() {
        initTestLogger(context)
    }

    @Test
    fun `doWork calls onCallStarted for OFFHOOK with eventElapsedRealtime`() =
        runTest {
            val subId = 1
            val eventTime = 12345L
            val inputData =
                Data
                    .Builder()
                    .putString(CallEventWorker.EXTRA_STATE, "OFFHOOK")
                    .putInt(CallEventWorker.EXTRA_SUB_ID, subId)
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
                    .putString(CallEventWorker.EXTRA_STATE, "IDLE")
                    .putInt(CallEventWorker.EXTRA_SUB_ID, subId)
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
                    .putString(CallEventWorker.EXTRA_STATE, "UNKNOWN_STATE")
                    .build()

            val worker =
                TestListenableWorkerBuilder<CallEventWorker>(context)
                    .setWorkerFactory(
                        object : WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ): ListenableWorker = CallEventWorker(appContext, workerParameters, callTracker)
                        },
                    ).setInputData(inputData)
                    .build()

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verifyNoInteractions(callTracker)
        }

    @Test
    fun `doWork ignores RINGING state without warnings or tracker calls`() =
        runTest {
            val inputData =
                Data
                    .Builder()
                    .putString(CallEventWorker.EXTRA_STATE, "RINGING")
                    .build()

            val worker =
                TestListenableWorkerBuilder<CallEventWorker>(context)
                    .setWorkerFactory(
                        object : WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ): ListenableWorker = CallEventWorker(appContext, workerParameters, callTracker)
                        },
                    ).setInputData(inputData)
                    .build()

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verifyNoInteractions(callTracker)
        }

    @Test
    fun `doWork retries on unexpected exception when runAttemptCount is less than MAX_EVENT_RUN_ATTEMPTS`() =
        runTest {
            whenever(callTracker.onCallStarted(any(), any())).thenThrow(RuntimeException("Unexpected error"))
            val inputData =
                Data
                    .Builder()
                    .putString(CallEventWorker.EXTRA_STATE, "OFFHOOK")
                    .putInt(CallEventWorker.EXTRA_SUB_ID, 1)
                    .putLong(CallEventWorker.EXTRA_EVENT_ELAPSED_REALTIME, 100L)
                    .build()

            val worker =
                TestListenableWorkerBuilder<CallEventWorker>(context)
                    .setWorkerFactory(
                        object : WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ): ListenableWorker = CallEventWorker(appContext, workerParameters, callTracker)
                        },
                    ).setInputData(inputData)
                    .setRunAttemptCount(0)
                    .build()

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
        }

    @Test
    fun `doWork fails on unexpected exception when runAttemptCount reaches MAX_EVENT_RUN_ATTEMPTS`() =
        runTest {
            whenever(callTracker.onCallStarted(any(), any())).thenThrow(RuntimeException("Unexpected error"))
            val inputData =
                Data
                    .Builder()
                    .putString(CallEventWorker.EXTRA_STATE, "OFFHOOK")
                    .putInt(CallEventWorker.EXTRA_SUB_ID, 1)
                    .putLong(CallEventWorker.EXTRA_EVENT_ELAPSED_REALTIME, 100L)
                    .build()

            val worker =
                TestListenableWorkerBuilder<CallEventWorker>(context)
                    .setWorkerFactory(
                        object : WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ): ListenableWorker = CallEventWorker(appContext, workerParameters, callTracker)
                        },
                    ).setInputData(inputData)
                    .setRunAttemptCount(CallEventWorker.MAX_EVENT_RUN_ATTEMPTS)
                    .build()

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.failure(), result)
        }

    @Test
    fun `doWork retries on SecurityException when runAttemptCount is less than MAX_EVENT_RUN_ATTEMPTS`() =
        runTest {
            whenever(callTracker.onCallStarted(any(), any())).thenThrow(SecurityException("Permission denied"))
            val inputData =
                Data
                    .Builder()
                    .putString(CallEventWorker.EXTRA_STATE, "OFFHOOK")
                    .putInt(CallEventWorker.EXTRA_SUB_ID, 1)
                    .putLong(CallEventWorker.EXTRA_EVENT_ELAPSED_REALTIME, 100L)
                    .build()

            val worker =
                TestListenableWorkerBuilder<CallEventWorker>(context)
                    .setWorkerFactory(
                        object : WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ): ListenableWorker = CallEventWorker(appContext, workerParameters, callTracker)
                        },
                    ).setInputData(inputData)
                    .setRunAttemptCount(0)
                    .build()

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
        }

    @Test
    fun `doWork fails on SecurityException when runAttemptCount reaches MAX_EVENT_RUN_ATTEMPTS`() =
        runTest {
            whenever(callTracker.onCallStarted(any(), any())).thenThrow(SecurityException("Permission denied"))
            val inputData =
                Data
                    .Builder()
                    .putString(CallEventWorker.EXTRA_STATE, "OFFHOOK")
                    .putInt(CallEventWorker.EXTRA_SUB_ID, 1)
                    .putLong(CallEventWorker.EXTRA_EVENT_ELAPSED_REALTIME, 100L)
                    .build()

            val worker =
                TestListenableWorkerBuilder<CallEventWorker>(context)
                    .setWorkerFactory(
                        object : WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ): ListenableWorker = CallEventWorker(appContext, workerParameters, callTracker)
                        },
                    ).setInputData(inputData)
                    .setRunAttemptCount(CallEventWorker.MAX_EVENT_RUN_ATTEMPTS)
                    .build()

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.failure(), result)
        }

    @Test
    fun `doWork retries on IOException when runAttemptCount is less than MAX_EVENT_RUN_ATTEMPTS`() =
        runTest {
            whenever(callTracker.onCallStarted(any(), any())).thenAnswer { throw IOException("IO error") }
            val inputData =
                Data
                    .Builder()
                    .putString(CallEventWorker.EXTRA_STATE, "OFFHOOK")
                    .putInt(CallEventWorker.EXTRA_SUB_ID, 1)
                    .putLong(CallEventWorker.EXTRA_EVENT_ELAPSED_REALTIME, 100L)
                    .build()

            val worker =
                TestListenableWorkerBuilder<CallEventWorker>(context)
                    .setWorkerFactory(
                        object : WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ): ListenableWorker = CallEventWorker(appContext, workerParameters, callTracker)
                        },
                    ).setInputData(inputData)
                    .setRunAttemptCount(0)
                    .build()

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
        }

    @Test
    fun `doWork fails on IOException when runAttemptCount reaches MAX_EVENT_RUN_ATTEMPTS`() =
        runTest {
            whenever(callTracker.onCallStarted(any(), any())).thenAnswer { throw IOException("IO error") }
            val inputData =
                Data
                    .Builder()
                    .putString(CallEventWorker.EXTRA_STATE, "OFFHOOK")
                    .putInt(CallEventWorker.EXTRA_SUB_ID, 1)
                    .putLong(CallEventWorker.EXTRA_EVENT_ELAPSED_REALTIME, 100L)
                    .build()

            val worker =
                TestListenableWorkerBuilder<CallEventWorker>(context)
                    .setWorkerFactory(
                        object : WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ): ListenableWorker = CallEventWorker(appContext, workerParameters, callTracker)
                        },
                    ).setInputData(inputData)
                    .setRunAttemptCount(CallEventWorker.MAX_EVENT_RUN_ATTEMPTS)
                    .build()

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.failure(), result)
        }

    @Test
    fun `doWork retries on DataStoreWriteTimeoutException when handling call start`() =
        runTest {
            whenever(
                callTracker.onCallStarted(
                    any(),
                    any(),
                ),
            ).thenAnswer { throw DataStoreWriteTimeoutException("saveCallSession") }
            val inputData =
                Data
                    .Builder()
                    .putString(CallEventWorker.EXTRA_STATE, "OFFHOOK")
                    .putInt(CallEventWorker.EXTRA_SUB_ID, 1)
                    .putLong(CallEventWorker.EXTRA_EVENT_ELAPSED_REALTIME, 100L)
                    .build()

            val worker =
                TestListenableWorkerBuilder<CallEventWorker>(context)
                    .setWorkerFactory(
                        object : WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ): ListenableWorker = CallEventWorker(appContext, workerParameters, callTracker)
                        },
                    ).setInputData(inputData)
                    .setRunAttemptCount(0)
                    .build()

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
        }

    @Test
    fun `doWork retries on DataStoreWriteTimeoutException when handling call end`() =
        runTest {
            whenever(
                callTracker.onCallEnded(
                    any(),
                    any(),
                ),
            ).thenAnswer { throw DataStoreWriteTimeoutException("clearCallSession") }
            val inputData =
                Data
                    .Builder()
                    .putString(CallEventWorker.EXTRA_STATE, "IDLE")
                    .putInt(CallEventWorker.EXTRA_SUB_ID, 1)
                    .putLong(CallEventWorker.EXTRA_EVENT_ELAPSED_REALTIME, 100L)
                    .build()

            val worker =
                TestListenableWorkerBuilder<CallEventWorker>(context)
                    .setWorkerFactory(
                        object : WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ): ListenableWorker = CallEventWorker(appContext, workerParameters, callTracker)
                        },
                    ).setInputData(inputData)
                    .setRunAttemptCount(0)
                    .build()

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
        }

    @Test
    fun `doWork returns retry when call event processing exceeds total timeout`() =
        runTest {
            val inputData =
                Data
                    .Builder()
                    .putString(CallEventWorker.EXTRA_STATE, TelephonyManager.EXTRA_STATE_IDLE)
                    .putInt(CallEventWorker.EXTRA_SUB_ID, 1)
                    .putLong(CallEventWorker.EXTRA_EVENT_ELAPSED_REALTIME, 1000L)
                    .build()
            val worker =
                TestListenableWorkerBuilder<CallEventWorker>(context)
                    .setWorkerFactory(
                        object : WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ): ListenableWorker = CallEventWorker(appContext, workerParameters, callTracker)
                        },
                    ).setInputData(inputData)
                    .setRunAttemptCount(0)
                    .build()

            whenever(callTracker.onCallEnded(any(), any())).thenAnswer { invocation ->
                val continuation = invocation.arguments.last() as kotlin.coroutines.Continuation<Unit>
                CoroutineScope(Dispatchers.Default).launch {
                    delay(1_000_000L)
                    continuation.resumeWith(Result.success(Unit))
                }
                COROUTINE_SUSPENDED
            }

            val result = worker.doWork()
            assertEquals(ListenableWorker.Result.retry(), result)
        }

    @Test
    fun `doWork returns failure when total timeout persists beyond max attempts`() =
        runTest {
            val inputData =
                Data
                    .Builder()
                    .putString(CallEventWorker.EXTRA_STATE, TelephonyManager.EXTRA_STATE_IDLE)
                    .putInt(CallEventWorker.EXTRA_SUB_ID, 1)
                    .putLong(CallEventWorker.EXTRA_EVENT_ELAPSED_REALTIME, 1000L)
                    .build()
            val worker =
                TestListenableWorkerBuilder<CallEventWorker>(context)
                    .setWorkerFactory(
                        object : WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ): ListenableWorker = CallEventWorker(appContext, workerParameters, callTracker)
                        },
                    ).setInputData(inputData)
                    .setRunAttemptCount(CallEventWorker.MAX_EVENT_RUN_ATTEMPTS)
                    .build()

            whenever(callTracker.onCallEnded(any(), any())).thenAnswer { invocation ->
                val continuation = invocation.arguments.last() as kotlin.coroutines.Continuation<Unit>
                CoroutineScope(Dispatchers.Default).launch {
                    delay(1_000_000L)
                    continuation.resumeWith(Result.success(Unit))
                }
                COROUTINE_SUSPENDED
            }

            val result = worker.doWork()
            assertEquals(ListenableWorker.Result.failure(), result)
        }
}
