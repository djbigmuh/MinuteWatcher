package com.example.minutewatcher.core.data.util

import com.example.minutewatcher.core.common.util.initTestLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreRetryHelperTest {
    private lateinit var helper: DataStoreRetryHelper

    @Before
    fun setup() {
        initTestLogger()
        helper = DataStoreRetryHelper()
    }

    @Test
    fun `readWithRetry returns success when first attempt succeeds`() =
        runTest {
            val result =
                helper.readWithRetry("test") {
                    flowOf("success")
                }
            assertTrue(result.isSuccess)
            assertEquals("success", result.getOrNull())
        }

    @Test
    fun `readWithRetry retries on failure and eventually succeeds`() =
        runTest {
            var attempts = 0
            val result =
                helper.readWithRetry("test", attempts = 3, retryDelayMs = 10) {
                    attempts++
                    if (attempts < 3) {
                        flow { throw IOException("Transient error") }
                    } else {
                        flowOf("eventual success")
                    }
                }
            assertTrue(result.isSuccess)
            assertEquals("eventual success", result.getOrNull())
            assertEquals(3, attempts)
        }

    @Test
    fun `readWithRetry returns failure after max attempts exhausted`() =
        runTest {
            var attempts = 0
            val result =
                helper.readWithRetry<String>("test", attempts = 3, retryDelayMs = 10) {
                    attempts++
                    flow { throw IOException("Persistent error") }
                }
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is IOException)
            assertEquals(3, attempts)
        }

    @Test
    fun `readWithRetry returns success immediately when flow emits null`() =
        runTest {
            var attempts = 0
            val result =
                helper.readWithRetry<String?>("test", attempts = 3, retryDelayMs = 1000) {
                    attempts++
                    flowOf(null)
                }
            assertTrue("Should be success", result.isSuccess)
            assertEquals(null, result.getOrNull())
            assertEquals("Should only attempt once", 1, attempts)
            assertEquals("Should have 0 virtual time elapsed as there were no retries", 0, testScheduler.currentTime)
        }

    @Test
    fun `readWithRetry retries on timeout when flow does not emit`() =
        runTest {
            var attempts = 0
            val result =
                helper.readWithRetry<String>("test", attempts = 3, retryDelayMs = 100) {
                    attempts++
                    flow {
                        // Never emits
                        awaitCancellation()
                    }
                }
            assertTrue("Should be failure", result.isFailure)
            assertEquals("Should attempt max times", 3, attempts)
            // Delays are 100, 200. Total 300ms.
            // Plus 3 * RETRY_READ_ATTEMPT_TIMEOUT_MS (usually 2000ms each)
            assertTrue("Virtual time should have elapsed", testScheduler.currentTime >= 300)
        }

    @Test
    fun `readWithRetry fails when attemptTimeoutMillis is exceeded`() =
        runTest {
            var attempts = 0
            val result =
                helper.readWithRetry<String>(
                    label = "test_timeout",
                    attempts = 3,
                    retryDelayMs = 10,
                    attemptTimeoutMillis = 100,
                ) {
                    attempts++
                    flow {
                        delay(200)
                        emit("delayed value")
                    }
                }
            assertTrue("Should fail on attempt timeout", result.isFailure)
            assertTrue(result.exceptionOrNull() is DataStoreReadTimeoutException)
            assertEquals("Should attempt max times", 3, attempts)
        }

    @Test
    fun `readWithRetry returns DataStoreReadTimeoutException on exhaustion by timeout`() =
        runTest {
            var attempts = 0
            val result =
                helper.readWithRetry<String>(
                    label = "test_label",
                    attempts = 3,
                    retryDelayMs = 10,
                    attemptTimeoutMillis = 100,
                ) {
                    attempts++
                    flow {
                        awaitCancellation()
                    }
                }
            assertTrue(result.isFailure)
            val exception = result.exceptionOrNull()
            assertTrue(
                "Expected DataStoreReadTimeoutException, but was $exception",
                exception is DataStoreReadTimeoutException,
            )
            assertEquals("DataStore read timeout (test_label)", exception?.message)
            assertEquals(3, attempts)
        }

    @Test
    fun `readWithRetry returns timeout exception when last attempt times out after earlier error`() =
        runTest {
            var attempts = 0
            val result =
                helper.readWithRetry<String>(
                    label = "test_mixed",
                    attempts = 2,
                    retryDelayMs = 10,
                    attemptTimeoutMillis = 100,
                ) {
                    attempts++
                    flow {
                        if (attempts == 1) {
                            throw IOException("Early disk failure")
                        } else {
                            awaitCancellation()
                        }
                    }
                }
            assertTrue(result.isFailure)
            val exception = result.exceptionOrNull()
            assertTrue(
                "Expected DataStoreReadTimeoutException for last attempt, but was $exception",
                exception is DataStoreReadTimeoutException,
            )
            assertEquals(2, attempts)
        }

    @Test
    fun `readWithRetry returns IOException when last attempt fails with IOException`() =
        runTest {
            var attempts = 0
            val result =
                helper.readWithRetry<String>(
                    label = "test_mixed_2",
                    attempts = 2,
                    retryDelayMs = 10,
                    attemptTimeoutMillis = 100,
                ) {
                    attempts++
                    flow {
                        if (attempts == 1) {
                            awaitCancellation()
                        } else {
                            throw IOException("Last attempt disk error")
                        }
                    }
                }
            assertTrue(result.isFailure)
            val exception = result.exceptionOrNull()
            assertTrue("Expected regular IOException for last attempt", exception is IOException)
            assertTrue(
                "Should not be DataStoreReadTimeoutException",
                exception !is DataStoreReadTimeoutException,
            )
            assertEquals("Last attempt disk error", exception?.message)
            assertEquals(2, attempts)
        }

    @Test
    fun `readWithRetry succeeds when attemptTimeoutMillis exceeds flow delay`() =
        runTest {
            var attempts = 0
            val result =
                helper.readWithRetry<String>(
                    label = "test_timeout_success",
                    attempts = 3,
                    retryDelayMs = 10,
                    attemptTimeoutMillis = 300,
                ) {
                    attempts++
                    flow {
                        delay(100)
                        emit("timely value")
                    }
                }
            assertTrue("Should succeed when emission is within attempt timeout", result.isSuccess)
            assertEquals("timely value", result.getOrNull())
            assertEquals("Should succeed on first attempt", 1, attempts)
        }
}
