package com.example.minutewatcher.feature.settings.ui

import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.WidgetSettingsLoader
import com.example.minutewatcher.core.model.WidgetSettingsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
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
            // Given: Saving succeeds, but notification throws CancellationException
            whenever(loader.saveGeneralSettings(any())).thenReturn(Unit)
            whenever(loader.saveSimSettings(any())).thenReturn(emptyList())
            whenever(
                dataChangeNotifier.notifyDataChanged(any(), any()),
            ).thenThrow(CancellationException("Notification cancelled"))

            val state = WidgetSettingsState()

            // Act: Save
            val result = saver.save(state)

            // Then: Result should be Success, not Failed
            assertEquals(SaveResult.Success, result)
        }

    @Test
    fun `save returns Failed if saving itself fails`() =
        runTest {
            // Given: Saving general settings throws Exception
            whenever(loader.saveGeneralSettings(any())).thenThrow(RuntimeException("DB failure"))

            val state = WidgetSettingsState()

            // Act: Save
            val result = saver.save(state)

            // Then: Result should be Failed
            assertEquals(SaveResult.Failed("Failed to save general settings"), result)
        }

    @Test
    fun `save performs defensive validation of input state`() =
        runTest {
            // Given: Invalid layout mode (too large)
            val invalidState = WidgetSettingsState(layoutMode = 99)

            // Act: Save
            saver.save(invalidState)

            // Then: verify that the loader received a validated state
            org.mockito.kotlin.verify(loader).saveGeneralSettings(
                org.mockito.kotlin.argThat {
                    this.layoutMode <= com.example.minutewatcher.core.model.Constants.Widget.MAX_LAYOUT_MODE
                },
            )
        }

    @Test
    fun `save returns PartialFailure if general settings saved but SIM settings throw exception`() =
        runTest {
            // Given: General settings save succeeds
            whenever(loader.saveGeneralSettings(any())).thenReturn(Unit)
            // But: SIM settings save throws an exception
            whenever(loader.saveSimSettings(any())).thenThrow(RuntimeException("SIM DB failure"))

            val state = WidgetSettingsState()

            // Act: Save
            val result = saver.save(state)

            // Then: Result should be PartialFailure
            assertTrue(
                "Result should be PartialFailure, but was $result",
                result is SaveResult.PartialFailure,
            )
            val partialFailure = result as SaveResult.PartialFailure
            assertTrue(partialFailure.message.contains("General settings saved"))

            // Verify notification still happened
            org.mockito.kotlin
                .verify(dataChangeNotifier)
                .notifyDataChanged(any(), any())
        }

    @Test
    fun `save returns Success when skipSimSettings is true`() =
        runTest {
            // Given: General settings save succeeds
            whenever(loader.saveGeneralSettings(any())).thenReturn(Unit)
            val state = WidgetSettingsState()

            // Act: Save with skipSimSettings = true
            val result = saver.save(state, skipSimSettings = true)

            // Then: Result should be Success, not PartialSuccess (per Instruction 18)
            assertEquals(SaveResult.Success, result)
        }
}
