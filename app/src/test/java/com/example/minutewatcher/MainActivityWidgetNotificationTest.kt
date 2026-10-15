package com.example.minutewatcher

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.Logger
import com.example.minutewatcher.core.common.util.NoOpLogger
import com.example.minutewatcher.core.common.util.resetAppLoggerForTest
import com.example.minutewatcher.core.domain.DataChangeNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class MainActivityWidgetNotificationTest {
    private val testDispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    private class FakeDataChangeNotifier : DataChangeNotifier {
        var callCount = 0
        var updateWidgetParam: Boolean? = null
        var forceParam: Boolean? = null
        var exceptionToThrow: Throwable? = null

        override suspend fun notifyDataChanged(
            updateWidget: Boolean,
            force: Boolean,
        ): Boolean {
            callCount++
            updateWidgetParam = updateWidget
            forceParam = force
            exceptionToThrow?.let { throw it }
            return true
        }
    }

    private class CapturingLogger : Logger by NoOpLogger() {
        val errorLogs = mutableListOf<Triple<String, String, Throwable?>>()

        override fun e(
            tag: String,
            message: String,
            throwable: Throwable?,
        ) {
            errorLogs.add(Triple(tag, message, throwable))
        }
    }

    private lateinit var capturingLogger: CapturingLogger

    @Before
    fun setup() {
        capturingLogger = CapturingLogger()
        AppLogger.init(ApplicationProvider.getApplicationContext(), capturingLogger)
    }

    @After
    fun tearDown() {
        resetAppLoggerForTest()
    }

    @Test
    fun `notifyDataChanged invokes notifier with correct parameters`() =
        runTest(testDispatcher) {
            val fakeNotifier = FakeDataChangeNotifier()
            val testScope = CoroutineScope(testDispatcher + SupervisorJob())
            val dispatcher = WidgetDataChangeDispatcher(fakeNotifier, testScope)

            withTimeout(5.seconds) {
                val job = dispatcher.notifyDataChanged("test reason")
                job.join()
            }

            assertEquals(1, fakeNotifier.callCount)
            assertEquals(true, fakeNotifier.updateWidgetParam)
            assertEquals(true, fakeNotifier.forceParam)
            assertTrue(testScope.isActive)
        }

    @Test
    fun `notifyDataChanged rethrows CancellationException without logging error`() =
        runTest(testDispatcher) {
            val fakeNotifier =
                FakeDataChangeNotifier().apply {
                    exceptionToThrow = CancellationException("Cancelled")
                }
            val testScope = CoroutineScope(testDispatcher + SupervisorJob())
            val dispatcher = WidgetDataChangeDispatcher(fakeNotifier, testScope)

            withTimeout(5.seconds) {
                val job = dispatcher.notifyDataChanged("test reason")
                job.join()
                assertTrue("Job should be cancelled by CancellationException", job.isCancelled)
            }

            assertTrue("Scope must remain active", testScope.isActive)
            assertEquals(0, capturingLogger.errorLogs.size)
        }

    @Test
    fun `notifyDataChanged catches Exception and logs error with reason without crashing scope`() =
        runTest(testDispatcher) {
            val testException = RuntimeException("Storage error")
            val fakeNotifier =
                FakeDataChangeNotifier().apply {
                    exceptionToThrow = testException
                }
            val testScope = CoroutineScope(testDispatcher + SupervisorJob())
            val dispatcher = WidgetDataChangeDispatcher(fakeNotifier, testScope)

            withTimeout(5.seconds) {
                val job = dispatcher.notifyDataChanged("test reason")
                job.join()
            }

            assertTrue("Scope must remain active after RuntimeException", testScope.isActive)
            assertEquals(1, capturingLogger.errorLogs.size)
            assertEquals(testException, capturingLogger.errorLogs[0].third)
            assertTrue(capturingLogger.errorLogs[0].second.contains("test reason"))
            assertTrue(capturingLogger.errorLogs[0].second.startsWith("WidgetDataChangeDispatcher:"))
        }

    @Test
    fun `notifyDataChanged catches Exception on language change and logs error with reason without crashing scope`() =
        runTest(testDispatcher) {
            val testException = RuntimeException("Unexpected error")
            val fakeNotifier =
                FakeDataChangeNotifier().apply {
                    exceptionToThrow = testException
                }
            val testScope = CoroutineScope(testDispatcher + SupervisorJob())
            val dispatcher = WidgetDataChangeDispatcher(fakeNotifier, testScope)

            withTimeout(5.seconds) {
                val job = dispatcher.notifyDataChanged("language change")
                job.join()
            }

            assertTrue("Scope must remain active after RuntimeException", testScope.isActive)
            assertEquals(1, capturingLogger.errorLogs.size)
            assertEquals(testException, capturingLogger.errorLogs[0].third)
            assertTrue(capturingLogger.errorLogs[0].second.contains("language change"))
            assertTrue(capturingLogger.errorLogs[0].second.startsWith("WidgetDataChangeDispatcher:"))
        }
}
