package com.example.minutewatcher.feature.widget

import android.content.Context
import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.WidgetUpdateResult
import com.example.minutewatcher.core.model.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

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
    fun `coalesced calls do not hang when update process throws Error`() =
        runTest {
            val oomRenderer =
                object : WidgetRenderer {
                    override suspend fun renderAll(
                        context: Context,
                        forceFull: Boolean,
                    ) {
                        delay(100)
                        throw OutOfMemoryError("Simulated OOM")
                    }
                }
            val updater = createUpdater(backgroundScope, oomRenderer)

            // First call starts the update
            val firstCall =
                async {
                    try {
                        updater.updateAll()
                    } catch (
                        // @Suppress("SwallowedException") is required because test
                        // catches expected exception from async job.
                        @Suppress("SwallowedException") e: Throwable,
                    ) {
                        // The first call will rethrow the error, so we catch it here to avoid
                        // failing the test prematurely.
                        WidgetUpdateResult.Failure(reason = WidgetUpdateResult.FailureReason.TERMINAL)
                    }
                }

            delay(50) // Ensure first call is active and has entered startNewUpdate

            // Second call should coalesce and wait for the same deferred
            val secondCall =
                async {
                    updater.updateAll()
                }

            // We expect secondCall to complete because deferred SHOULD be
            // completed by startNewUpdate's catch(Throwable)
            // If it hangs, the test will timeout.
            val result2 = secondCall.await()

            assertTrue(
                "Second call should return failure, not hang. Result was: $result2",
                result2 is WidgetUpdateResult.Failure,
            )
            assertEquals(WidgetUpdateResult.FailureReason.TERMINAL, (result2 as WidgetUpdateResult.Failure).reason)
            assertTrue("Result should be marked as coalesced", result2.wasCoalesced)

            firstCall.await()
        }
}
