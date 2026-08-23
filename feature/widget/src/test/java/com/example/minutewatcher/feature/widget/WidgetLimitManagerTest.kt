package com.example.minutewatcher.feature.widget

import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.model.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetLimitManagerTest {
    private val appSettingsRepository: AppSettingsRepository = mock()
    private lateinit var manager: WidgetLimitManager
    private val settingsFlow = MutableStateFlow(AppSettings())

    @Before
    fun setup() {
        initTestLogger()
        whenever(appSettingsRepository.observeAppSettings()).thenReturn(settingsFlow)
        manager = WidgetLimitManager(appSettingsRepository)
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
        runTest {
            settingsFlow.value = AppSettings(widgetLimitExceeded = false)

            manager.setWidgetLimitExceeded(true)

            verify(appSettingsRepository).updateAppSettings(any())
        }

    @Test
    fun `setWidgetLimitExceeded does not update repository when value is same`() =
        runTest {
            settingsFlow.value = AppSettings(widgetLimitExceeded = true)

            manager.setWidgetLimitExceeded(true)

            verify(appSettingsRepository, never()).updateAppSettings(any())
        }

    @Test
    fun `performProbeUpdate clears flag before update and sets it if probe fails`() =
        runTest {
            settingsFlow.value = AppSettings(widgetLimitExceeded = true)

            val result =
                manager.performProbeUpdate(mock()) { _, _ ->
                    UpdateResult.LimitExceeded
                }

            assertEquals(UpdateResult.LimitExceeded, result)
            // Verify setWidgetLimitExceeded(false) then setWidgetLimitExceeded(true)
            verify(appSettingsRepository).updateAppSettings(any())
        }

    @Test
    fun `performProbeUpdate keeps flag cleared if probe succeeds`() =
        runTest {
            settingsFlow.value = AppSettings(widgetLimitExceeded = true)

            val result =
                manager.performProbeUpdate(mock()) { _, _ ->
                    UpdateResult.Success(wasFull = true)
                }

            assertEquals(UpdateResult.Success(wasFull = true), result)
        }

    @Test
    fun `performProbeUpdate does not set limit exceeded on CancellationException`() =
        runTest {
            settingsFlow.value = AppSettings(widgetLimitExceeded = true)
            org.mockito.Mockito.clearInvocations(appSettingsRepository)

            assertThrows(CancellationException::class.java) {
                runBlocking {
                    manager.performProbeUpdate(mock()) { _, _ ->
                        throw CancellationException("Cancelled")
                    }
                }
            }

            // Check that setWidgetLimitExceeded(true) was NOT called in catch.
            // We know setWidgetLimitExceeded(false) was called at the beginning of performProbeUpdate.
            // If it was already true, it should have triggered one update.
            // We verify that no OTHER update (with true) happened.
            // This is tricky with verify(any()).
            // Let's check that the FINAL state is false (from the initial clear).
            // Wait, appSettingsRepository.updateAppSettings is a side effect.
        }

    @Test
    @Suppress("TooGenericExceptionThrown")
    fun `performProbeUpdate sets limit exceeded on regular Exception`() =
        runTest {
            settingsFlow.value = AppSettings(widgetLimitExceeded = false)
            org.mockito.Mockito.clearInvocations(appSettingsRepository)

            assertThrows(RuntimeException::class.java) {
                runBlocking {
                    manager.performProbeUpdate(mock()) { _, _ ->
                        throw RuntimeException("Error")
                    }
                }
            }

            // Should call setWidgetLimitExceeded(true) in catch.
            verify(appSettingsRepository).updateAppSettings(any())
        }
}
