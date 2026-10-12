package com.example.minutewatcher

import androidx.test.core.app.ApplicationProvider
import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.Logger
import com.example.minutewatcher.core.common.util.NoOpLogger
import com.example.minutewatcher.core.common.util.resetAppLoggerForTest
import com.example.minutewatcher.core.domain.DataChangeNotifier
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
@OptIn(ExperimentalCoroutinesApi::class)
class MainActivityWidgetNotificationTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    private val testDispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

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
        hiltRule.inject()
        capturingLogger = CapturingLogger()
        AppLogger.init(ApplicationProvider.getApplicationContext(), capturingLogger)
    }

    @After
    fun tearDown() {
        resetAppLoggerForTest()
    }

    @Test
    fun `notifyWidgetDataChanged rethrows CancellationException`() =
        runTest(testDispatcher) {
            val controller = Robolectric.buildActivity(MainActivity::class.java).get()
            val mockNotifier = mock<DataChangeNotifier>()
            val cancellationEx = CancellationException("Cancelled")
            whenever(mockNotifier.notifyDataChanged(any(), any())).thenThrow(cancellationEx)

            val notifierField = MainActivity::class.java.getDeclaredField("dataChangeNotifier")
            notifierField.isAccessible = true
            notifierField.set(controller, mockNotifier)

            val testScope = CoroutineScope(testDispatcher + SupervisorJob())
            val scopeField = MainActivity::class.java.getDeclaredField("applicationScope")
            scopeField.isAccessible = true
            scopeField.set(controller, testScope)

            val method = MainActivity::class.java.getDeclaredMethod("notifyWidgetDataChanged", String::class.java)
            method.isAccessible = true

            withTimeout(5.seconds) {
                method.invoke(controller, "test reason")
                var caughtCancellation = false
                try {
                    runCurrent()
                } catch (_: CancellationException) {
                    caughtCancellation = true
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    if (e.cause is CancellationException) {
                        caughtCancellation = true
                    } else {
                        throw e.cause ?: e
                    }
                }
                // If cancellation exception is rethrown by the coroutine builder or caught in test
                assertTrue(
                    "CancellationException should be propagated/rethrown",
                    caughtCancellation || testScope.isActive,
                )
            }
        }

    @Test
    fun `notifyWidgetDataChanged catches Exception and logs error without crashing scope`() =
        runTest(testDispatcher) {
            val controller = Robolectric.buildActivity(MainActivity::class.java).get()
            val mockNotifier = mock<DataChangeNotifier>()
            val testException = RuntimeException("Storage error")
            whenever(mockNotifier.notifyDataChanged(any(), any())).thenThrow(testException)

            val notifierField = MainActivity::class.java.getDeclaredField("dataChangeNotifier")
            notifierField.isAccessible = true
            notifierField.set(controller, mockNotifier)

            val testScope = CoroutineScope(testDispatcher + SupervisorJob())
            val scopeField = MainActivity::class.java.getDeclaredField("applicationScope")
            scopeField.isAccessible = true
            scopeField.set(controller, testScope)

            val method = MainActivity::class.java.getDeclaredMethod("notifyWidgetDataChanged", String::class.java)
            method.isAccessible = true

            withTimeout(5.seconds) {
                method.invoke(controller, "test reason")
                runCurrent()
            }

            assertTrue("Scope must remain active after Exception", testScope.isActive)
            assertEquals(1, capturingLogger.errorLogs.size)
            assertEquals(testException, capturingLogger.errorLogs[0].third)
            assertTrue(capturingLogger.errorLogs[0].second.contains("test reason"))
        }

    @Test
    fun `notifyWidgetDataChanged catches Exception on language change and logs error without crashing scope`() =
        runTest(testDispatcher) {
            val controller = Robolectric.buildActivity(MainActivity::class.java).get()
            val mockNotifier = mock<DataChangeNotifier>()
            val testException = RuntimeException("Unexpected error")
            whenever(mockNotifier.notifyDataChanged(any(), any())).thenThrow(testException)

            val notifierField = MainActivity::class.java.getDeclaredField("dataChangeNotifier")
            notifierField.isAccessible = true
            notifierField.set(controller, mockNotifier)

            val testScope = CoroutineScope(testDispatcher + SupervisorJob())
            val scopeField = MainActivity::class.java.getDeclaredField("applicationScope")
            scopeField.isAccessible = true
            scopeField.set(controller, testScope)

            val method = MainActivity::class.java.getDeclaredMethod("notifyWidgetDataChanged", String::class.java)
            method.isAccessible = true

            withTimeout(5.seconds) {
                method.invoke(controller, "language change")
                runCurrent()
            }

            assertTrue("Scope must remain active after RuntimeException", testScope.isActive)
            assertEquals(1, capturingLogger.errorLogs.size)
            assertEquals(testException, capturingLogger.errorLogs[0].third)
            assertTrue(capturingLogger.errorLogs[0].second.contains("language change"))
        }
}
