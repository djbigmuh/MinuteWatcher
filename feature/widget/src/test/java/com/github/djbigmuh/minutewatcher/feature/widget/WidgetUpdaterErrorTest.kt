package com.github.djbigmuh.minutewatcher.feature.widget

import android.content.Context
import android.os.TransactionTooLargeException
import com.github.djbigmuh.minutewatcher.core.domain.WidgetUpdateResult
import com.github.djbigmuh.minutewatcher.core.model.AppSettings
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class WidgetUpdaterErrorTest : BaseWidgetUpdaterFixture() {
    @Test(timeout = 10000)
    fun `OutOfMemoryError thrown by renderer propagates out of updateAll`() =
        runTest {
            val oomRenderer =
                object : WidgetRenderer {
                    override suspend fun renderAll(context: Context): Unit = throw OutOfMemoryError("Simulated OOM")
                }
            val updater = createUpdater(backgroundScope, oomRenderer)

            val result =
                runCatching {
                    withTimeout(5.seconds) {
                        updater.updateAll()
                    }
                }
            assertTrue(
                "Should propagate OutOfMemoryError, but got: ${result.exceptionOrNull()}",
                result.exceptionOrNull() is OutOfMemoryError,
            )
        }

    @Test(timeout = 10000)
    fun `activeUpdate is released after OutOfMemoryError so subsequent update runs independently`() =
        runTest {
            val renderCount = AtomicInteger(0)
            val oomRenderer =
                object : WidgetRenderer {
                    override suspend fun renderAll(context: Context): Unit =
                        run {
                            renderCount.incrementAndGet()
                            throw OutOfMemoryError("Simulated OOM")
                        }
                }
            val updater = createUpdater(backgroundScope, oomRenderer)

            runCatching {
                withTimeout(5.seconds) {
                    updater.updateAll()
                }
            }

            assertEquals(1, renderCount.get())

            runCatching {
                withTimeout(5.seconds) {
                    updater.updateAll()
                }
            }

            assertEquals(2, renderCount.get())
        }

    @Test
    fun `renderer throwing IllegalStateException with TransactionTooLargeException triggers limit exceeded`() =
        runTest {
            whenever(renderer.renderAll(any()))
                .thenThrow(IllegalStateException("Binder", TransactionTooLargeException()))
                .thenReturn(Unit)
            val updater = createUpdater(backgroundScope, renderer)
            appSettingsRepository.settingsFlow.value = AppSettings(widgetLimitExceeded = false)

            val result =
                withTimeout(5.seconds) {
                    updater.updateAll()
                }

            assertTrue("Update should succeed on retry in simplified mode", result is WidgetUpdateResult.Success)
            assertTrue(
                "Widget limit exceeded flag should be set",
                appSettingsRepository.settingsFlow.value.widgetLimitExceeded,
            )
            verify(renderer, times(2)).renderAll(any())
        }
}
