package com.example.minutewatcher.core.data.util

import com.example.minutewatcher.core.common.util.initTestLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
}
