package host.serv.djbigmuh.minutewatcher.core.common.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class FlowUtilsTest {
    private class RecordingLogger : Logger {
        val warnings = mutableListOf<String>()

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
            warnings.add(message)
        }

        override fun e(
            tag: String,
            message: String,
            throwable: Throwable?,
        ) {
            // no-op
        }
    }

    private lateinit var recordingLogger: RecordingLogger

    @Before
    fun setup() {
        recordingLogger = RecordingLogger()
        AppLogger.init(mock(), recordingLogger)
    }

    @After
    fun tearDown() {
        AppLogger.resetForTest()
    }

    /**
     * Edge case tests verify behavior under error conditions.
     * These are critical for production stability on custom ROMs
     * where system services may degrade or fail unexpectedly.
     */

    @Test
    fun `returns value when flow emits before timeout`() =
        runTest {
            val flow = flowOf("success")
            val result = flow.firstWithTimeout(100.milliseconds)
            assertEquals("success", result)
        }

    @Test
    fun `returns null on timeout instead of blocking indefinitely`() =
        runTest {
            val flow =
                flow<String> {
                    delay(200.milliseconds)
                    emit("late")
                }
            val result = flow.firstWithTimeout(100.milliseconds)
            assertNull(result)
        }

    @Test
    fun `propagates CancellationException without retry`() =
        runTest {
            val flow =
                flow<String> {
                    throw CancellationException("Intentional cancellation")
                }

            try {
                flow.firstWithTimeout(100.milliseconds)
                org.junit.Assert.fail("Should have thrown CancellationException")
            } catch (e: CancellationException) {
                assertEquals("Intentional cancellation", e.message)
            }
        }

    @Test
    fun `firstWithTimeout long overload returns emitted value or null`() =
        runTest {
            val flow = flowOf("longVal")
            assertEquals("longVal", flow.firstWithTimeout(100L))

            val nullFlow = flowOf<String?>(null)
            assertNull(nullFlow.firstWithTimeout(100L))

            val timeoutFlow =
                flow<String?> {
                    delay(200.milliseconds)
                }
            assertNull(timeoutFlow.firstWithTimeout(100L))
        }

    @Test
    fun `firstWithTimeout duration overload returns emitted value or null`() =
        runTest {
            val flow = flowOf("durationVal")
            assertEquals("durationVal", flow.firstWithTimeout(100.milliseconds))

            val nullFlow = flowOf<String?>(null)
            assertNull(nullFlow.firstWithTimeout(100.milliseconds))

            val timeoutFlow =
                flow<String?> {
                    delay(200.milliseconds)
                }
            assertNull(timeoutFlow.firstWithTimeout(100.milliseconds))
        }

    @Test
    fun `firstWithTimeoutOrLog handles value, null emission, and timeout`() =
        runTest {
            val flow = flowOf<String?>(null)
            val resultNull = flow.firstWithTimeoutOrLog(100.milliseconds.inWholeMilliseconds, "TestTag", "key1")
            assertNull(resultNull)
            assertTrue("Should not log timeout warning when null is emitted", recordingLogger.warnings.isEmpty())

            val timeoutFlow =
                flow<String?> {
                    delay(200.milliseconds)
                }
            val resultTimeout =
                timeoutFlow.firstWithTimeoutOrLog(
                    100.milliseconds.inWholeMilliseconds,
                    "TestTag",
                    "key1",
                )
            assertNull(resultTimeout)
            assertEquals(1, recordingLogger.warnings.size)
            assertTrue(recordingLogger.warnings[0].contains("Timeout reading from Flow in TestTag"))
        }

    @Test
    fun `firstWithTimeoutOrDefault handles value, null emission, and timeout`() =
        runTest {
            val flow = flowOf<String?>(null)
            val resultNull =
                flow.firstWithTimeoutOrDefault(
                    100.milliseconds.inWholeMilliseconds,
                    "TestTag",
                    "defaultVal",
                    "key1",
                )
            assertEquals("defaultVal", resultNull)
            assertTrue("Should not log timeout warning when null is emitted", recordingLogger.warnings.isEmpty())

            val timeoutFlow =
                flow<String?> {
                    delay(200.milliseconds)
                }
            val resultTimeout =
                timeoutFlow.firstWithTimeoutOrDefault(
                    100.milliseconds.inWholeMilliseconds,
                    "TestTag",
                    "defaultVal",
                    "key1",
                )
            assertEquals("defaultVal", resultTimeout)
            assertEquals(1, recordingLogger.warnings.size)
            assertTrue(recordingLogger.warnings[0].contains("Timeout reading from Flow in TestTag"))
        }
}
