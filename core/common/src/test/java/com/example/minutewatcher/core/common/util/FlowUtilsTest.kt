package com.example.minutewatcher.core.common.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class FlowUtilsTest {
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
}
