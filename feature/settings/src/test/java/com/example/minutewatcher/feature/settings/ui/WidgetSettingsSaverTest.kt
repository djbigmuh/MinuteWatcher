package com.example.minutewatcher.feature.settings.ui

import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.WidgetSettingsLoader
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.WidgetSettingsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetSettingsSaverTest {
    private val loader: WidgetSettingsLoader = mock()
    private val dataChangeNotifier: DataChangeNotifier = mock()
    private lateinit var saver: WidgetSettingsSaver

    @Before
    fun setup() {
        initTestLogger()
        saver = WidgetSettingsSaver(loader, dataChangeNotifier)
    }

    @Test
    fun `save returns Success even if notifyDataChanged is cancelled`() =
        runTest {
            whenever(loader.saveGeneralSettings(any())).thenReturn(Unit)
            whenever(loader.saveSimSettings(any())).thenReturn(emptyList())
            whenever(
                dataChangeNotifier.notifyDataChanged(any(), any()),
            ).thenThrow(CancellationException("Notification cancelled"))

            val state = WidgetSettingsState()

            val result = saver.save(state)

            assertEquals(SaveResult.Success, result)
        }

    @Test
    fun `save returns Failed GENERAL if saving general settings throws exception`() =
        runTest {
            whenever(loader.saveGeneralSettings(any())).thenThrow(RuntimeException("DB failure"))

            val state = WidgetSettingsState()

            val result = saver.save(state)

            assertEquals(SaveResult.Failed(SaveFailureReason.GENERAL), result)
        }

    @Test
    fun `save returns Failed NOT_LOADED if settings load failed`() =
        runTest {
            val state = WidgetSettingsState(settingsLoadFailed = true)

            val result = saver.save(state)

            assertEquals(SaveResult.Failed(SaveFailureReason.NOT_LOADED), result)
        }

    @Test
    fun `save performs defensive validation of input state`() =
        runTest {
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
            whenever(loader.saveGeneralSettings(any())).thenReturn(Unit)
            whenever(loader.saveSimSettings(any())).thenReturn(listOf(0, 1))

            val state = WidgetSettingsState()

            val result = saver.save(state)

            assertEquals(SaveResult.PartialFailure(listOf(0, 1)), result)

            verify(dataChangeNotifier)
                .notifyDataChanged(any(), any())
        }

    @Test
    fun `save returns PartialFailure with empty list when saveSimSettings throws exception`() =
        runTest {
            whenever(loader.saveGeneralSettings(any())).thenReturn(Unit)
            whenever(loader.saveSimSettings(any())).thenThrow(RuntimeException("SIM DB failure"))

            val state = WidgetSettingsState()

            val result = saver.save(state)

            assertTrue("Result should be PartialFailure", result is SaveResult.PartialFailure)
            assertEquals(emptyList<Int>(), (result as SaveResult.PartialFailure).failedSlots)

            verify(dataChangeNotifier)
                .notifyDataChanged(any(), any())
        }

    @Test
    fun `save returns Success when skipSimSettings is true`() =
        runTest {
            whenever(loader.saveGeneralSettings(any())).thenReturn(Unit)
            val state = WidgetSettingsState()

            val result = saver.save(state, skipSimSettings = true)

            assertEquals(SaveResult.Success, result)
        }
}
