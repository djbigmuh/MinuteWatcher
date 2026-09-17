package com.example.minutewatcher.feature.widget

import android.content.Context
import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.model.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.lang.reflect.Field

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
    fun `test cancellation during updateAll finally causes activeUpdate leak`() =
        runTest(testDispatcher) {
            // 1. Get the mutex via reflection
            val mutexField: Field = WidgetUpdaterImpl::class.java.getDeclaredField("coalescingMutex")
            mutexField.isAccessible = true
            val coalescingMutex = mutexField.get(updater) as Mutex

            val activeUpdateField: Field = WidgetUpdaterImpl::class.java.getDeclaredField("activeUpdate")
            activeUpdateField.isAccessible = true

            val reachedUpdate = CompletableDeferred<Unit>()
            whenever(renderer.renderAll(any(), any())).thenAnswer {
                reachedUpdate.complete(Unit)
                mock()
            }

            val job =
                launch {
                    updater.updateAll()
                }

            reachedUpdate.await()
            coalescingMutex.lock() // Block the finally block

            job.cancelAndJoin() // Cancel while it's waiting for mutex in finally

            coalescingMutex.unlock()

            // 2. Check if activeUpdate leaked.
            val activeUpdate = activeUpdateField.get(updater)
            assert(activeUpdate == null) { "activeUpdate should have been cleared to null" }
        }
}
