package com.example.minutewatcher.feature.widget

import android.content.Context
import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.WidgetUpdateResult
import com.example.minutewatcher.core.model.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetUpdaterCancellationTest {
    private val context: Context = mock()
    private val appSettingsRepository: AppSettingsRepository = mock()
    private val renderer: WidgetRenderer = mock()
    private val retryManager = WidgetUpdateRetryManager()
    private val errorClassifier = WidgetErrorClassifier()

    private lateinit var updater: WidgetUpdaterImpl
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setup() {
        initTestLogger()
        whenever(appSettingsRepository.observeAppSettings()).thenReturn(MutableStateFlow(AppSettings()))
        whenever(appSettingsRepository.getAppSettings()).thenReturn(MutableStateFlow(AppSettings()))

        val limitManager = WidgetLimitManager(appSettingsRepository, testScope)
        updater =
            WidgetUpdaterImpl(
                context,
                appSettingsRepository,
                renderer,
                retryManager,
                limitManager,
                errorClassifier,
            )
    }

    @Test
    fun `updateAll cancellation clears activeUpdate and allows subsequent updates`() =
        runTest(testDispatcher) {
            val reachedUpdate = CompletableDeferred<Unit>()
            val finishUpdate = CompletableDeferred<Unit>()

            val fakeRenderer =
                object : WidgetRenderer {
                    override suspend fun renderAll(
                        context: Context,
                        forceFull: Boolean,
                    ) {
                        reachedUpdate.complete(Unit)
                        withTimeoutOrNull(30.seconds) { finishUpdate.await() }
                            ?: throw AssertionError("Timed out waiting for finishUpdate in fakeRenderer")
                    }
                }

            val limitManager = WidgetLimitManager(appSettingsRepository, backgroundScope)
            val testUpdater =
                WidgetUpdaterImpl(
                    context,
                    appSettingsRepository,
                    fakeRenderer,
                    retryManager,
                    limitManager,
                    errorClassifier,
                )

            // 1. Start first update
            val job =
                launch {
                    testUpdater.updateAll()
                }

            withTimeoutOrNull(30.seconds) { reachedUpdate.await() }
                ?: throw AssertionError("Timed out waiting for reachedUpdate")

            // 2. Cancel it while it's "rendering" (suspended in finishUpdate.await())
            job.cancelAndJoin()

            // 3. Immediately call updateAll again.
            // If activeUpdate was cleared, this should be a NEW update (not coalesced).
            val result = testUpdater.updateAll()

            val wasCoalesced =
                when (result) {
                    is WidgetUpdateResult.Success -> result.wasCoalesced
                    is WidgetUpdateResult.Failure -> result.wasCoalesced
                }
            assert(!wasCoalesced) { "Subsequent update should not be coalesced with cancelled one" }
        }
}
