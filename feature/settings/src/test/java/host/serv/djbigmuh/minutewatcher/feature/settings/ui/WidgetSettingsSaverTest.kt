package host.serv.djbigmuh.minutewatcher.feature.settings.ui

import host.serv.djbigmuh.minutewatcher.core.common.util.initTestLogger
import host.serv.djbigmuh.minutewatcher.core.domain.DataChangeNotifier
import host.serv.djbigmuh.minutewatcher.core.domain.WidgetSettingsLoader
import host.serv.djbigmuh.minutewatcher.core.model.Constants
import host.serv.djbigmuh.minutewatcher.core.model.WidgetSettingsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetSettingsSaverTest {
    private val loader: WidgetSettingsLoader = mock()
    private val dataChangeNotifier: DataChangeNotifier = mock()

    @Before
    fun setup() {
        initTestLogger()
    }

    @Test
    fun `save returns Success even if notifyDataChanged is canceled`() =
        runTest {
            val saver = WidgetSettingsSaver(loader, dataChangeNotifier, backgroundScope)
            whenever(loader.saveGeneralSettings(any())).thenReturn(Unit)
            whenever(loader.saveSimSettings(any())).thenReturn(emptyList())
            whenever(
                dataChangeNotifier.notifyDataChanged(any(), any()),
            ).thenThrow(RuntimeException("Notification failure"))

            val state = WidgetSettingsState()

            val result = saver.save(state)

            assertEquals(SaveResult.Success, result)
        }

    @Test
    fun `save returns Failed GENERAL if saving general settings throws exception`() =
        runTest {
            val saver = WidgetSettingsSaver(loader, dataChangeNotifier, backgroundScope)
            whenever(loader.saveGeneralSettings(any())).thenThrow(RuntimeException("DB failure"))

            val state = WidgetSettingsState()

            val result = saver.save(state)

            assertEquals(SaveResult.Failed(SaveFailureReason.GENERAL), result)
        }

    @Test
    fun `save returns Failed NOT_LOADED if settings load failed`() =
        runTest {
            val saver = WidgetSettingsSaver(loader, dataChangeNotifier, backgroundScope)
            val state = WidgetSettingsState(settingsLoadFailed = true)

            val result = saver.save(state)

            assertEquals(SaveResult.Failed(SaveFailureReason.NOT_LOADED), result)
        }

    @Test
    fun `save performs defensive validation of input state`() =
        runTest {
            val saver = WidgetSettingsSaver(loader, dataChangeNotifier, backgroundScope)
            val invalidState = WidgetSettingsState(layoutMode = 99)

            saver.save(invalidState)

            verify(loader).saveGeneralSettings(
                argThat {
                    this.layoutMode <= Constants.Widget.MAX_SELECTABLE_LAYOUT_MODE
                },
            )
        }

    @Test
    fun `save returns PartialFailure with failed slots list when saveSimSettings returns failed slots`() =
        runTest {
            val saver = WidgetSettingsSaver(loader, dataChangeNotifier, backgroundScope)
            whenever(loader.saveGeneralSettings(any())).thenReturn(Unit)
            whenever(loader.saveSimSettings(any())).thenReturn(listOf(0, 1))

            val state = WidgetSettingsState()

            val result = saver.save(state)

            assertEquals(SaveResult.PartialFailure(listOf(0, 1)), result)

            runCurrent()
            verify(dataChangeNotifier)
                .notifyDataChanged(any(), any())
        }

    @Test
    fun `save returns PartialFailure with empty list when saveSimSettings throws exception`() =
        runTest {
            val saver = WidgetSettingsSaver(loader, dataChangeNotifier, backgroundScope)
            whenever(loader.saveGeneralSettings(any())).thenReturn(Unit)
            whenever(loader.saveSimSettings(any())).thenThrow(RuntimeException("SIM DB failure"))

            val state = WidgetSettingsState()

            val result = saver.save(state)

            assertTrue("Result should be PartialFailure", result is SaveResult.PartialFailure)
            assertEquals(emptyList<Int>(), (result as SaveResult.PartialFailure).failedSlots)

            runCurrent()
            verify(dataChangeNotifier)
                .notifyDataChanged(any(), any())
        }

    @Test
    fun `save returns Success when skipSimSettings is true`() =
        runTest {
            val saver = WidgetSettingsSaver(loader, dataChangeNotifier, backgroundScope)
            whenever(loader.saveGeneralSettings(any())).thenReturn(Unit)
            val state = WidgetSettingsState()

            val result = saver.save(state, skipSimSettings = true)

            assertEquals(SaveResult.Success, result)
        }

    @Test
    fun `canceled caller scope still executes notifyDataChanged on appScope`() =
        runTest {
            val saver = WidgetSettingsSaver(loader, dataChangeNotifier, backgroundScope)
            whenever(loader.saveGeneralSettings(any())).thenReturn(Unit)
            whenever(loader.saveSimSettings(any())).thenReturn(emptyList())

            val callingJob =
                launch {
                    saver.save(WidgetSettingsState())
                }
            runCurrent()
            callingJob.cancel()
            runCurrent()

            verify(dataChangeNotifier, times(1)).notifyDataChanged(updateWidget = true, force = true)
        }

    @Test
    fun `notifyDataChanged rethrows CancellationException`() =
        runTest {
            val saver = WidgetSettingsSaver(loader, dataChangeNotifier, backgroundScope)
            whenever(
                dataChangeNotifier.notifyDataChanged(any(), any()),
            ).thenThrow(CancellationException("Notification canceled"))

            assertThrows(CancellationException::class.java) {
                runBlocking {
                    saver.notifyDataChanged()
                }
            }
        }

    @Test
    fun `notifyDataChanged retries 3 times when returning false`() =
        runTest {
            val saver = WidgetSettingsSaver(loader, dataChangeNotifier, backgroundScope)
            whenever(dataChangeNotifier.notifyDataChanged(any(), any())).thenReturn(false)

            saver.notifyDataChanged()

            verify(dataChangeNotifier, times(3)).notifyDataChanged(updateWidget = true, force = true)
        }

    @Test
    fun `notifyDataChanged retries 3 times when throwing non-cancellation exception`() =
        runTest {
            val saver = WidgetSettingsSaver(loader, dataChangeNotifier, backgroundScope)
            whenever(dataChangeNotifier.notifyDataChanged(any(), any())).thenThrow(RuntimeException("Error"))

            saver.notifyDataChanged()

            verify(dataChangeNotifier, times(3)).notifyDataChanged(updateWidget = true, force = true)
        }

    @Test
    fun `notifyDataChanged succeeds on second attempt and stops retrying`() =
        runTest {
            val saver = WidgetSettingsSaver(loader, dataChangeNotifier, backgroundScope)
            whenever(dataChangeNotifier.notifyDataChanged(any(), any()))
                .thenReturn(false)
                .thenReturn(true)

            saver.notifyDataChanged()

            verify(dataChangeNotifier, times(2)).notifyDataChanged(updateWidget = true, force = true)
        }

    @Test
    fun `notifyDataChanged propagates CancellationException immediately without retries`() =
        runTest {
            val saver = WidgetSettingsSaver(loader, dataChangeNotifier, backgroundScope)
            whenever(dataChangeNotifier.notifyDataChanged(any(), any()))
                .thenThrow(CancellationException("Canceled"))

            assertThrows(CancellationException::class.java) {
                runBlocking {
                    saver.notifyDataChanged()
                }
            }

            verify(dataChangeNotifier, times(1)).notifyDataChanged(updateWidget = true, force = true)
        }

    @Test
    fun `slow notifier call longer than SHORT_TIMEOUT_MS is not canceled and completes`() =
        runTest {
            var completed = false
            val slowNotifier =
                object : DataChangeNotifier {
                    override suspend fun notifyDataChanged(
                        updateWidget: Boolean,
                        force: Boolean,
                    ): Boolean {
                        delay(6_000L)
                        completed = true
                        return true
                    }
                }
            val saver = WidgetSettingsSaver(loader, slowNotifier, backgroundScope)

            saver.notifyDataChanged()
            assertTrue(completed)
        }
}
