package host.serv.djbigmuh.minutewatcher.feature.widget

import host.serv.djbigmuh.minutewatcher.core.common.util.initTestLogger
import host.serv.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import host.serv.djbigmuh.minutewatcher.core.model.AppSettings
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
import org.mockito.Mockito
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
    fun `shouldProbe returns true every PROBE_INTERVAL_UPDATES`() {
        manager.resetCounter()
        repeat(WidgetLimitManager.PROBE_INTERVAL_UPDATES - 1) {
            manager.incrementUpdateCounter()
            assertFalse(manager.shouldProbe())
        }
        manager.incrementUpdateCounter()
        assertTrue(manager.shouldProbe())
    }

    @Test
    fun `resetCounter resets the counter`() {
        repeat(WidgetLimitManager.PROBE_INTERVAL_UPDATES - 1) {
            manager.incrementUpdateCounter()
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
    fun `setWidgetLimitExceeded updates repository even when value is same`() =
        runTest(testDispatcher) {
            settingsFlow.value = AppSettings(widgetLimitExceeded = true)

            manager.setWidgetLimitExceeded(value = true)

            verify(appSettingsRepository).updateAppSettings(any())
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
    fun `performProbeUpdate does NOT set limit exceeded on regular Exception`() =
        runTest(testDispatcher) {
            settingsFlow.value = AppSettings(widgetLimitExceeded = false)
            clearInvocations(appSettingsRepository)

            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    manager.performProbeUpdate(mock()) { _, _ ->
                        throw IllegalStateException("Error")
                    }
                }
            }

            // Should NOT call setWidgetLimitExceeded(true) in catch anymore.
            verify(appSettingsRepository, never()).updateAppSettings(any())
        }
}
