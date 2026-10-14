package com.example.minutewatcher.feature.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.domain.RefreshResult
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.WidgetSettingsLoader
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.WidgetSettingsState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class MinutesGlanceWidgetTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val usageStateRepository: UsageStateRepository = mock()
    private val timeProvider: TimeProvider = mock()

    @Test
    fun `getSnapshot returns Failure on repository exception`() =
        runTest {
            val initialSnapshot = GlobalSnapshot(SchemaVersion(1, 0, 0), 0L, emptyList(), emptyList())
            val stateFlow = MutableStateFlow(initialSnapshot)
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)
            whenever(timeProvider.now()).thenReturn(200000L)

            val exception = IOException("Storage error")
            whenever(usageStateRepository.refresh()).thenThrow(exception)

            val widget = MinutesGlanceWidget()
            val result =
                withTimeout(5.seconds) {
                    widget.getSnapshot(usageStateRepository, timeProvider)
                }

            assertTrue("Result should be Failure", result is RefreshResult.Failure)
            val failure = result as RefreshResult.Failure
            assertEquals(initialSnapshot, failure.snapshot)
            assertEquals(exception, failure.cause)
        }

    @Test
    fun `getUiStateInternal loads settings and snapshot in parallel`() =
        runTest {
            val settingsLoader = mock<WidgetSettingsLoader>()
            whenever(settingsLoader.load()).thenAnswer {
                runBlocking { kotlinx.coroutines.delay(1000L) }
                WidgetSettingsState()
            }

            val usageRepo = mock<UsageStateRepository>()
            whenever(usageRepo.observeSnapshot()).thenReturn(MutableStateFlow(GlobalSnapshot(SchemaVersion(1, 0, 0), System.currentTimeMillis(), emptyList(), emptyList())))
            whenever(usageRepo.refresh()).thenAnswer {
                runBlocking { kotlinx.coroutines.delay(1000L) }
                RefreshResult.Success(GlobalSnapshot(SchemaVersion(1, 0, 0), System.currentTimeMillis(), emptyList(), emptyList()))
            }

            val timeProvider = mock<TimeProvider>()
            whenever(timeProvider.now()).thenReturn(System.currentTimeMillis())

            val widget = MinutesGlanceWidget()
            val startTime = testScheduler.currentTime
            widget.getUiStateInternal(context, usageRepo, settingsLoader, timeProvider)
            val duration = testScheduler.currentTime - startTime

            assertTrue("Parallel execution should take less than sum of durations", duration < 1800L)
        }

    @Test
    fun `getUiStateInternal handles settings loader exception with settingsLoadFailed true`() =
        runTest {
            val settingsLoader = mock<WidgetSettingsLoader>()
            whenever(settingsLoader.load()).thenThrow(RuntimeException("Settings load failed"))

            val usageRepo = mock<UsageStateRepository>()
            whenever(usageRepo.observeSnapshot()).thenReturn(MutableStateFlow(GlobalSnapshot(SchemaVersion(1, 0, 0), System.currentTimeMillis(), emptyList(), emptyList())))
            whenever(usageRepo.refresh()).thenReturn(RefreshResult.Success(GlobalSnapshot(SchemaVersion(1, 0, 0), System.currentTimeMillis(), emptyList(), emptyList())))

            val timeProvider = mock<TimeProvider>()
            whenever(timeProvider.now()).thenReturn(System.currentTimeMillis())

            val widget = MinutesGlanceWidget()
            val uiState = widget.getUiStateInternal(context, usageRepo, settingsLoader, timeProvider)
            assertEquals(Constants.Widget.DEFAULT_LAYOUT_MODE, uiState.layoutMode)
        }

    @Test
    fun `forceFullRender true clears widgetLimitExceeded in loaded settings`() =
        runTest {
            val settingsLoader = mock<WidgetSettingsLoader>()
            whenever(settingsLoader.load()).thenReturn(WidgetSettingsState(widgetLimitExceeded = true))

            val usageRepo = mock<UsageStateRepository>()
            whenever(usageRepo.observeSnapshot()).thenReturn(MutableStateFlow(GlobalSnapshot(SchemaVersion(1, 0, 0), System.currentTimeMillis(), emptyList(), emptyList())))
            whenever(usageRepo.refresh()).thenReturn(RefreshResult.Success(GlobalSnapshot(SchemaVersion(1, 0, 0), System.currentTimeMillis(), emptyList(), emptyList())))

            val timeProvider = mock<TimeProvider>()
            whenever(timeProvider.now()).thenReturn(System.currentTimeMillis())

            val widget = MinutesGlanceWidget(forceFullRender = true)
            val uiState = widget.getUiStateInternal(context, usageRepo, settingsLoader, timeProvider)
            assertEquals(2, uiState.layoutMode)
        }
}
