package com.github.djbigmuh.minutewatcher.feature.settings.ui

import com.github.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import com.github.djbigmuh.minutewatcher.core.common.util.initTestLogger
import com.github.djbigmuh.minutewatcher.core.domain.WidgetSettingsLoader
import com.github.djbigmuh.minutewatcher.core.domain.WidgetUpdateResult
import com.github.djbigmuh.minutewatcher.core.domain.WidgetUpdater
import com.github.djbigmuh.minutewatcher.core.model.Constants
import com.github.djbigmuh.minutewatcher.core.model.WidgetSettingsState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetSettingsSaverTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val loader: WidgetSettingsLoader = mock()
    private val widgetUpdater: WidgetUpdater = mock()

    private class FakeWidgetSettingsLoader(
        var currentSettings: WidgetSettingsState = WidgetSettingsState(),
    ) : WidgetSettingsLoader {
        var savedGeneralCount = 0
        var savedSimCount = 0

        override suspend fun load(): WidgetSettingsState = currentSettings

        override suspend fun saveGeneralSettings(state: WidgetSettingsState) {
            savedGeneralCount++
            currentSettings =
                currentSettings.copy(
                    layoutMode = state.layoutMode,
                    simArrangement = state.simArrangement,
                )
        }

        override suspend fun saveSimSettings(state: WidgetSettingsState): List<Int> {
            savedSimCount++
            return emptyList()
        }
    }

    private class FakeWidgetUpdater : WidgetUpdater {
        var updateCount = 0

        override suspend fun updateAll(): WidgetUpdateResult {
            updateCount++
            return WidgetUpdateResult.Success()
        }

        override suspend fun recover() = Unit
    }

    @Before
    fun setup() {
        initTestLogger()
    }

    @Test
    fun `save returns Success even if widgetUpdater throws non-cancellation exception`() =
        runTest {
            val fakeLoader =
                FakeWidgetSettingsLoader().apply {
                    currentSettings = WidgetSettingsState(layoutMode = 0)
                }
            val throwingUpdater =
                object : WidgetUpdater {
                    override suspend fun updateAll(): WidgetUpdateResult = throw RuntimeException("Update failure")

                    override suspend fun recover() = Unit
                }
            val saver =
                WidgetSettingsSaver(
                    fakeLoader,
                    throwingUpdater,
                    mainDispatcherRule.testDispatcher,
                    backgroundScope,
                )

            val state = WidgetSettingsState(layoutMode = 1)
            val result = saver.save(state)
            runCurrent()

            assertEquals(SaveResult.Success, result)
        }

    @Test
    fun `save returns Failed GENERAL if saving general settings throws exception`() =
        runTest {
            val saver =
                WidgetSettingsSaver(
                    loader,
                    widgetUpdater,
                    mainDispatcherRule.testDispatcher,
                    backgroundScope,
                )
            whenever(loader.saveGeneralSettings(any())).thenThrow(RuntimeException("DB failure"))

            val state = WidgetSettingsState(layoutMode = 1)

            val result = saver.save(state)

            assertEquals(SaveResult.Failed(SaveFailureReason.GENERAL), result)
        }

    @Test
    fun `save returns Failed NOT_LOADED if settings load failed`() =
        runTest {
            val saver =
                WidgetSettingsSaver(
                    loader,
                    widgetUpdater,
                    mainDispatcherRule.testDispatcher,
                    backgroundScope,
                )
            val state = WidgetSettingsState(settingsLoadFailed = true)

            val result = saver.save(state)

            assertEquals(SaveResult.Failed(SaveFailureReason.NOT_LOADED), result)
        }

    @Test
    fun `save performs defensive validation of input state`() =
        runTest {
            val saver =
                WidgetSettingsSaver(
                    loader,
                    widgetUpdater,
                    mainDispatcherRule.testDispatcher,
                    backgroundScope,
                )
            whenever(loader.saveGeneralSettings(any())).thenReturn(Unit)
            whenever(widgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Success())
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
            val saver =
                WidgetSettingsSaver(
                    loader,
                    widgetUpdater,
                    mainDispatcherRule.testDispatcher,
                    backgroundScope,
                )
            whenever(loader.saveGeneralSettings(any())).thenReturn(Unit)
            whenever(loader.saveSimSettings(any())).thenReturn(listOf(0, 1))
            whenever(widgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Success())

            val state = WidgetSettingsState(layoutMode = 1)

            val result = saver.save(state)

            assertEquals(SaveResult.PartialFailure(listOf(0, 1)), result)

            runCurrent()
            verify(widgetUpdater)
                .updateAll()
        }

    @Test
    fun `save returns PartialFailure with empty list when saveSimSettings throws exception`() =
        runTest {
            val saver =
                WidgetSettingsSaver(
                    loader,
                    widgetUpdater,
                    mainDispatcherRule.testDispatcher,
                    backgroundScope,
                )
            whenever(loader.saveGeneralSettings(any())).thenReturn(Unit)
            whenever(loader.saveSimSettings(any())).thenThrow(RuntimeException("SIM DB failure"))
            whenever(widgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Success())

            val state = WidgetSettingsState(layoutMode = 1)

            val result = saver.save(state)

            assertTrue("Result should be PartialFailure", result is SaveResult.PartialFailure)
            assertEquals(emptyList<Int>(), (result as SaveResult.PartialFailure).failedSlots)

            runCurrent()
            verify(widgetUpdater)
                .updateAll()
        }

    @Test
    fun `save returns Success when skipSimSettings is true`() =
        runTest {
            val saver =
                WidgetSettingsSaver(
                    loader,
                    widgetUpdater,
                    mainDispatcherRule.testDispatcher,
                    backgroundScope,
                )
            whenever(loader.saveGeneralSettings(any())).thenReturn(Unit)
            whenever(widgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Success())
            val state = WidgetSettingsState(layoutMode = 1)

            val result = saver.save(state, skipSimSettings = true)

            assertEquals(SaveResult.Success, result)
        }

    @Test
    fun `canceled caller scope still executes widget update on appScope`() =
        runTest {
            val fakeLoader =
                FakeWidgetSettingsLoader().apply {
                    currentSettings = WidgetSettingsState(layoutMode = 0)
                }
            val fakeUpdater = FakeWidgetUpdater()
            val saver =
                WidgetSettingsSaver(
                    fakeLoader,
                    fakeUpdater,
                    mainDispatcherRule.testDispatcher,
                    backgroundScope,
                )

            val callingJob =
                launch {
                    saver.save(WidgetSettingsState(layoutMode = 1))
                }
            runCurrent()
            callingJob.cancel()
            runCurrent()

            assertEquals(1, fakeUpdater.updateCount)
        }

    @Test
    fun `save triggers widget update`() =
        runTest {
            val fakeLoader =
                FakeWidgetSettingsLoader().apply {
                    currentSettings = WidgetSettingsState(layoutMode = 0)
                }
            val fakeUpdater = FakeWidgetUpdater()
            val saver =
                WidgetSettingsSaver(
                    fakeLoader,
                    fakeUpdater,
                    mainDispatcherRule.testDispatcher,
                    backgroundScope,
                )

            val newState = WidgetSettingsState(layoutMode = 1)
            val result = saver.save(newState)
            runCurrent()

            assertEquals(SaveResult.Success, result)
            assertEquals(1, fakeUpdater.updateCount)
        }
}
