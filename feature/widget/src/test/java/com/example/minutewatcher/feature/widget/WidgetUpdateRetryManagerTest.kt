package com.example.minutewatcher.feature.widget

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetUpdateRetryManagerTest {
    private val manager = WidgetUpdateRetryManager()

    @Before
    fun setup() {
        com.example.minutewatcher.core.common.util.AppLogger.init(
            mock(),
            com.example.minutewatcher.core.common.util
                .NoOpLogger(),
        )
    }

    @Test
    fun `updateWithRetry returns true on immediate success`() =
        runTest {
            val result =
                manager.updateWithRetry {
                    UpdateOutcome.Success()
                }
            assertTrue(result)
        }

    @Test
    fun `updateWithRetry returns true after retries`() =
        runTest {
            var attempts = 0
            val result =
                manager.updateWithRetry {
                    attempts++
                    if (attempts < 3) UpdateOutcome.Retry else UpdateOutcome.Success()
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
            assertEquals(com.example.minutewatcher.core.model.Constants.Widget.MAX_UPDATE_ATTEMPTS, attempts)
        }

    @Test
    fun `updateWithRetry returns false on Stop outcome`() =
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
}
