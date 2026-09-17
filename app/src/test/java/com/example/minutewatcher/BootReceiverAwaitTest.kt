package com.example.minutewatcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.Operation
import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.data.WorkScheduler
import com.google.common.util.concurrent.SettableFuture
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
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

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `onReceive awaits work enqueuing`() = runTest {
        val finished = AtomicBoolean(false)
        val mockPendingResult = mock<BroadcastReceiver.PendingResult>()
        whenever(mockPendingResult.finish()).thenAnswer { finished.set(true) }

        val operation = mock<Operation>()
        val future = SettableFuture.create<Operation.State.SUCCESS>()
        whenever(operation.result).thenReturn(future)

        val workScheduler = mock<WorkScheduler>()
        whenever(workScheduler.scheduleInitialSetup(any())).thenReturn(operation)

        val receiver = object : BootReceiver() {
            override fun acquirePendingResult() = mockPendingResult
        }
        receiver.workScheduler = workScheduler

        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        runCurrent()
        // Should not have finished yet because future is not set
        assertFalse("Should not have finished yet", finished.get())

        future.set(Operation.SUCCESS)
        runCurrent()

        assertTrue("Should have finished after future completion", finished.get())
    }
}
