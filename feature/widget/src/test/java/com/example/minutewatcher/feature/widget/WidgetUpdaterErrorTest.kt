package com.example.minutewatcher.feature.widget

import android.content.Context
import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.WidgetUpdateResult
import com.example.minutewatcher.core.model.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
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
    fun `coalesced calls do not hang when update process throws Error`() =
        runTest {
            val updateStartedSignal = CompletableDeferred<Unit>()
            val finishSignal = CompletableDeferred<Unit>()

            val oomRenderer =
                object : WidgetRenderer {
                    override suspend fun renderAll(
                        context: Context,
                        forceFull: Boolean,
                    ) {
                        updateStartedSignal.complete(Unit)
                        withTimeoutOrNull(30.seconds) { finishSignal.await() }
                            ?: throw AssertionError("Timed out waiting for finishSignal")
                        throw OutOfMemoryError("Simulated OOM")
                    }
                }
            val updater = createUpdater(backgroundScope, oomRenderer)

            // First call starts the update
            val firstCall =
                async {
                    updater.updateAll()
                }

            withTimeoutOrNull(30.seconds) { updateStartedSignal.await() }
                ?: throw AssertionError("Timed out waiting for updateStartedSignal")

            // Second call should coalesce and wait for the same deferred
            val secondCall =
                async {
                    updater.updateAll()
                }

            finishSignal.complete(Unit)

            val result1 =
                withTimeoutOrNull(30.seconds) { firstCall.await() }
                    ?: throw AssertionError("Timed out waiting for firstCall")

            assertTrue(
                "First call should return terminal failure without throwing. Result was: $result1",
                result1 is WidgetUpdateResult.Failure,
            )
            assertEquals(WidgetUpdateResult.FailureReason.TERMINAL, (result1 as WidgetUpdateResult.Failure).reason)

            val result2 =
                withTimeoutOrNull(30.seconds) { secondCall.await() }
                    ?: throw AssertionError("Timed out waiting for secondCall")

            assertTrue(
                "Second call should return failure, not hang. Result was: $result2",
                result2 is WidgetUpdateResult.Failure,
            )
            assertEquals(WidgetUpdateResult.FailureReason.TERMINAL, (result2 as WidgetUpdateResult.Failure).reason)
            assertTrue("Result should be marked as coalesced", result2.wasCoalesced)
        }
}
