package com.example.minutewatcher.feature.widget

import com.example.minutewatcher.core.common.util.initTestLogger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetUpdateRetryManagerTest {
    private val manager = WidgetUpdateRetryManager()

    @Before
    fun setup() {
        initTestLogger()
    }

    @Test
    fun `updateWithRetry returns true on immediate success`() =
        runTest {
            var attempts = 0
            val result =
                manager.updateWithRetry {
                    attempts++
                    UpdateOutcome.Success
                }
            assertTrue(result)
            assertEquals(1, attempts)
        }

    @Test
    fun `updateWithRetry retries and returns true on eventual success`() =
        runTest {
            var attempts = 0
            val result =
                manager.updateWithRetry {
                    attempts++
                    if (attempts < 3) UpdateOutcome.Retry else UpdateOutcome.Success
                }
            assertTrue(result)
            assertEquals(3, attempts)
        }

    @Test
    fun `updateWithRetry returns false after max attempts exhausted`() =
        runTest {
            var attempts = 0
            val result =
                manager.updateWithRetry {
                    attempts++
                    UpdateOutcome.Retry
                }
            assertFalse(result)
            assertEquals(3, attempts)
        }

    @Test
    fun `updateWithRetry returns false immediately on Stop outcome`() =
        runTest {
            var attempts = 0
            val result =
                manager.updateWithRetry {
                    attempts++
                    UpdateOutcome.Stop
                }
            assertFalse(result)
            assertEquals(1, attempts)
        }

    @Test
    fun `updateWithRetry returns false immediately on LimitExceeded outcome`() =
        runTest {
            var attempts = 0
            val result =
                manager.updateWithRetry {
                    attempts++
                    UpdateOutcome.LimitExceeded(wasFull = true)
                }
            assertFalse(result)
            assertEquals(1, attempts)
        }
}
