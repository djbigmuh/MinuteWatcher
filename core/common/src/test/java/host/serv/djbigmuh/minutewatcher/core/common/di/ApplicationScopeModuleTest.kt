package host.serv.djbigmuh.minutewatcher.core.common.di

import android.content.Context
import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import host.serv.djbigmuh.minutewatcher.core.common.util.Logger
import host.serv.djbigmuh.minutewatcher.core.common.util.NoOpLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

@OptIn(ExperimentalCoroutinesApi::class)
class ApplicationScopeModuleTest {
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

    private val capturingLogger = CapturingLogger()

    @Before
    fun setUp() {
        val mockContext =
            mock<Context> {
                on { filesDir } doReturn null
            }
        AppLogger.init(mockContext, capturingLogger)
    }

    @After
    fun tearDown() {
        AppLogger.resetForTest()
    }

    @Test
    fun `uncaught exception in ApplicationScope is logged and scope remains active`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val scope = ApplicationScopeModule.provideApplicationScope(testDispatcher)

            val exception = RuntimeException("Test exception in child coroutine")
            scope.launch {
                throw exception
            }

            testScheduler.advanceUntilIdle()

            assertEquals(1, capturingLogger.errorLogs.size)
            val log = capturingLogger.errorLogs[0]
            assertTrue(log.second.contains("ApplicationScope"))
            assertEquals(exception, log.third)

            assertTrue("Scope must remain active after child exception", scope.isActive)

            var secondExecuted = false
            scope.launch {
                secondExecuted = true
            }

            testScheduler.advanceUntilIdle()
            assertTrue("Subsequent coroutines must execute in active scope", secondExecuted)
        }
}
