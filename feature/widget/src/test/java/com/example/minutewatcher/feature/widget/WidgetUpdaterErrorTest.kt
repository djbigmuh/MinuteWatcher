package com.example.minutewatcher.feature.widget

import android.content.Context
import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.model.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetUpdaterErrorTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var context: Context
    private val appSettingsRepository: AppSettingsRepository = mock()
    private val renderer: WidgetRenderer = mock()
    private val retryManager = WidgetUpdateRetryManager()
    private val errorClassifier = WidgetErrorClassifier()

    @Before
    fun setup() {
        context = mock()
        val mockSettings = MutableStateFlow(AppSettings(widgetLimitExceeded = false))
        whenever(appSettingsRepository.observeAppSettings()).thenReturn(mockSettings)
        whenever(appSettingsRepository.getAppSettings()).thenReturn(mockSettings)
    }

    private fun createUpdater(
        scope: CoroutineScope,
        customRenderer: WidgetRenderer = renderer,
    ): WidgetUpdaterImpl {
        val limitManager = WidgetLimitManager(appSettingsRepository, scope)
        return WidgetUpdaterImpl(
            context = context,
            appSettingsRepository = appSettingsRepository,
            renderer = customRenderer,
            retryManager = retryManager,
            limitManager = limitManager,
            errorClassifier = errorClassifier,
        )
    }

    @Test(timeout = 10000)
    fun `OutOfMemoryError thrown by renderer propagates out of updateAll`() =
        runTest {
            val oomRenderer =
                object : WidgetRenderer {
                    override suspend fun renderAll(
                        context: Context,
                        forceFull: Boolean,
                    ): Unit = throw OutOfMemoryError("Simulated OOM")
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
                    override suspend fun renderAll(
                        context: Context,
                        forceFull: Boolean,
                    ): Unit =
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

            // Subsequent update should run independently and throw OOM again
            runCatching {
                withTimeout(5.seconds) {
                    updater.updateAll()
                }
            }

            assertEquals(2, renderCount.get())
        }
}
