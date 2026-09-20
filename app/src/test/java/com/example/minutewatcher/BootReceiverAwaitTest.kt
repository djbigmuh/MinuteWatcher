package com.example.minutewatcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.Operation
import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.Logger
import com.example.minutewatcher.core.data.WorkScheduler
import com.google.common.util.concurrent.SettableFuture
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BootReceiverAwaitTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    private val scheduler = TestCoroutineScheduler()
    private val testDispatcher = StandardTestDispatcher(scheduler)

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    private lateinit var context: Context

    private val capturedErrors = mutableListOf<Throwable?>()
    private val capturingLogger =
        object : Logger {
            override fun d(
                tag: String,
                message: String,
            ) {
                // no-op
            }

            override fun w(
                tag: String,
                message: String,
                throwable: Throwable?,
            ) {
                // no-op
            }

            override fun e(
                tag: String,
                message: String,
                throwable: Throwable?,
            ) {
                capturedErrors.add(throwable)
            }
        }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        AppLogger.init(context, capturingLogger)
    }

    @After
    fun tearDown() {
        AppLogger.resetForTest()
    }

    @Test
    fun `onReceive awaits work enqueuing`() =
        runTest(scheduler) {
            val finished = AtomicBoolean(false)
            val mockPendingResult = mock<BroadcastReceiver.PendingResult>()
            whenever(mockPendingResult.finish()).thenAnswer { finished.set(true) }

            val operation = mock<Operation>()
            val future = SettableFuture.create<Operation.State.SUCCESS>()
            whenever(operation.result).thenReturn(future)

            val workScheduler = mock<WorkScheduler>()
            // Match explicit force = true to detect stubbing mismatch
            whenever(workScheduler.scheduleInitialSetup(force = true, triggeredByBoot = true)).thenReturn(operation)

            val receiver =
                object : BootReceiver() {
                    override fun acquirePendingResult() = mockPendingResult

                    override fun provideWorkScheduler() = workScheduler

                    override fun asyncDispatcher() = testDispatcher
                }

            receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

            runCurrent()

            // Positive assertions
            verify(workScheduler).scheduleInitialSetup(force = true, triggeredByBoot = true)
            verify(operation).result
            assertTrue("No errors should be logged", capturedErrors.isEmpty())

            // Should not have finished yet because future is not set
            assertFalse("Should not have finished yet", finished.get())
            verify(mockPendingResult, never()).finish()

            future.set(Operation.SUCCESS)
            runCurrent()

            assertTrue("Should have finished after future completion", finished.get())
            verify(mockPendingResult).finish()
        }
}
