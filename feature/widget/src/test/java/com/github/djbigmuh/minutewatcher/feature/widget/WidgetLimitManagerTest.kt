package com.github.djbigmuh.minutewatcher.feature.widget

import com.github.djbigmuh.minutewatcher.core.common.util.initTestLogger
import com.github.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import com.github.djbigmuh.minutewatcher.core.model.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
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
        whenever(appSettingsRepository.getAppSettings()).thenReturn(settingsFlow)
        manager = WidgetLimitManager(appSettingsRepository, testScope)
    }

    @Test
    fun `isLimitExceeded returns null initially`() {
        assertEquals(null, manager.isLimitExceeded())
        manager.start()
        testDispatcher.scheduler.runCurrent()
        assertFalse(requireNotNull(manager.isLimitExceeded()))
    }

    @Test
    fun `constructor does not start observation automatically`() =
        runTest(testDispatcher) {
            assertEquals(null, manager.isLimitExceeded())
            testDispatcher.scheduler.advanceTimeBy(5000L)
            testDispatcher.scheduler.runCurrent()
            assertEquals(null, manager.isLimitExceeded())
            verify(appSettingsRepository, never()).observeAppSettings()
        }

    @Test
    fun `start is idempotent`() =
        runTest(testDispatcher) {
            manager.start()
            manager.start()
            testDispatcher.scheduler.runCurrent()
            verify(appSettingsRepository, org.mockito.kotlin.times(1)).observeAppSettings()
        }

    @Test
    fun `updateCache updates isLimitExceeded without repository access`() {
        manager.updateCache(true)
        assertTrue(requireNotNull(manager.isLimitExceeded()))
        manager.updateCache(false)
        assertFalse(requireNotNull(manager.isLimitExceeded()))
    }

    @Test
    fun `setWidgetLimitExceeded handles repository exception without throwing`() =
        runTest(testDispatcher) {
            whenever(appSettingsRepository.updateAppSettings(any())).thenThrow(RuntimeException("DB error"))
            manager.setWidgetLimitExceeded(true)
            assertTrue(requireNotNull(manager.isLimitExceeded()))
        }

    @Test
    fun `isLimitExceeded returns value set by setWidgetLimitExceeded synchronously`() =
        runTest(testDispatcher) {
            manager.setWidgetLimitExceeded(value = true)
            assertTrue(requireNotNull(manager.isLimitExceeded()))

            manager.setWidgetLimitExceeded(value = false)
            assertFalse(requireNotNull(manager.isLimitExceeded()))
        }

    @Test
    fun `setWidgetLimitExceeded updates repository when value changes`() =
        runTest(testDispatcher) {
            settingsFlow.value = AppSettings(widgetLimitExceeded = false)

            manager.setWidgetLimitExceeded(value = true)

            verify(appSettingsRepository).updateAppSettings(any())
        }

    @Test
    fun `calling setWidgetLimitExceeded(false) when cache is false does not access repository`() =
        runTest(testDispatcher) {
            manager.updateCache(false)
            clearInvocations(appSettingsRepository)

            manager.setWidgetLimitExceeded(false)

            verify(appSettingsRepository, never()).getAppSettings()
            verify(appSettingsRepository, never()).updateAppSettings(any())
        }

    @Test
    fun `calling setWidgetLimitExceeded(true) when cache is false accesses repo once and updates cache`() =
        runTest(testDispatcher) {
            manager.updateCache(false)
            clearInvocations(appSettingsRepository)

            manager.setWidgetLimitExceeded(true)

            verify(appSettingsRepository, never()).getAppSettings()
            verify(appSettingsRepository, org.mockito.kotlin.times(1)).updateAppSettings(any())
            assertEquals(true, manager.isLimitExceeded())
        }
}
