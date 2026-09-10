package com.example.minutewatcher.feature.widget

import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.model.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WidgetLimitManagerTest {
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val appSettingsRepository: AppSettingsRepository = mock()
    private lateinit var manager: WidgetLimitManager
    private val settingsFlow = MutableStateFlow(AppSettings())

    @Before
    fun setup() {
        initTestLogger()
        whenever(appSettingsRepository.observeAppSettings()).thenReturn(settingsFlow)
        manager = WidgetLimitManager(appSettingsRepository, testScope)
    }

    @Test
    fun `isLimitExceeded returns null initially`() {
        assertEquals(null, manager.isLimitExceeded())
        testDispatcher.scheduler.runCurrent()
        assertEquals(false, manager.isLimitExceeded())
    }

    @Test
    fun `isLimitExceeded returns value set by setWidgetLimitExceeded synchronously`() =
        runTest(testDispatcher) {
            manager.setWidgetLimitExceeded(value = true)
            assertEquals(true, manager.isLimitExceeded())

            manager.setWidgetLimitExceeded(value = false)
            assertEquals(false, manager.isLimitExceeded())
        }

    @Test
    fun `shouldProbe returns true every PROBE_INTERVAL_UPDATES`() {
        manager.resetCounter()
        repeat(WidgetLimitManager.PROBE_INTERVAL_UPDATES - 1) {
            assertFalse(manager.shouldProbe())
        }
        assertTrue(manager.shouldProbe())
    }

    @Test
    fun `resetCounter resets the counter`() {
        repeat(WidgetLimitManager.PROBE_INTERVAL_UPDATES - 1) {
            manager.shouldProbe()
        }
        manager.resetCounter()
        assertFalse(manager.shouldProbe())
    }

    @Test
    fun `setWidgetLimitExceeded updates repository when value changes`() =
        runTest(testDispatcher) {
            settingsFlow.value = AppSettings(widgetLimitExceeded = false)

            manager.setWidgetLimitExceeded(value = true)

            verify(appSettingsRepository).updateAppSettings(any())
        }

    @Test
    fun `setWidgetLimitExceeded does not update repository when value is same`() =
        runTest(testDispatcher) {
            settingsFlow.value = AppSettings(widgetLimitExceeded = true)

            manager.setWidgetLimitExceeded(value = true)

            verify(appSettingsRepository, never()).updateAppSettings(any())
        }

    @Test
    fun `performProbeUpdate sets flag to true if probe results in LimitExceeded`() =
        runTest(testDispatcher) {
            settingsFlow.value = AppSettings(widgetLimitExceeded = false)

            val result =
                manager.performProbeUpdate(mock()) { _, _ ->
                    UpdateResult.LimitExceeded(wasFull = true)
                }

            assertEquals(UpdateResult.LimitExceeded(wasFull = true), result)
            verify(appSettingsRepository).updateAppSettings(any())
        }

    @Test
    fun `performProbeUpdate does NOT set flag to true if probe fails transiently`() =
        runTest(testDispatcher) {
            settingsFlow.value = AppSettings(widgetLimitExceeded = false)
            Mockito.clearInvocations(appSettingsRepository)

            val result =
                manager.performProbeUpdate(mock()) { _, _ ->
                    UpdateResult.GenericFailure(isRetryable = true)
                }

            assertEquals(UpdateResult.GenericFailure(isRetryable = true), result)
            verify(appSettingsRepository, never()).updateAppSettings(any())
        }

    @Test
    fun `performProbeUpdate sets flag to false if probe succeeds`() =
        runTest(testDispatcher) {
            settingsFlow.value = AppSettings(widgetLimitExceeded = true)

            val result =
                manager.performProbeUpdate(mock()) { _, _ ->
                    UpdateResult.Success(wasFull = true)
                }

            assertEquals(UpdateResult.Success(wasFull = true), result)
            verify(appSettingsRepository).updateAppSettings(any())
        }

    @Test
    fun `performProbeUpdate does not set limit exceeded on CancellationException`() =
        runTest(testDispatcher) {
            settingsFlow.value = AppSettings(widgetLimitExceeded = false)
            clearInvocations(appSettingsRepository)

            assertThrows(CancellationException::class.java) {
                runBlocking {
                    manager.performProbeUpdate(mock()) { _, _ ->
                        throw CancellationException("Cancelled")
                    }
                }
            }

            verify(appSettingsRepository, never()).updateAppSettings(any())
        }

    @Test
    @Suppress("TooGenericExceptionThrown")
    fun `performProbeUpdate does NOT set limit exceeded on regular Exception`() =
        runTest(testDispatcher) {
            settingsFlow.value = AppSettings(widgetLimitExceeded = false)
            clearInvocations(appSettingsRepository)

            assertThrows(RuntimeException::class.java) {
                runBlocking {
                    manager.performProbeUpdate(mock()) { _, _ ->
                        throw RuntimeException("Error")
                    }
                }
            }

            // Should NOT call setWidgetLimitExceeded(true) in catch anymore.
            verify(appSettingsRepository, never()).updateAppSettings(any())
        }
}
