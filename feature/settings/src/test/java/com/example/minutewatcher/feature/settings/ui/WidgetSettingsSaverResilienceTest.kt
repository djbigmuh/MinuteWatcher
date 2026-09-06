package com.example.minutewatcher.feature.settings.ui

import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.domain.WidgetSaveResult
import com.example.minutewatcher.core.domain.WidgetSettingsLoader
import com.example.minutewatcher.core.model.WidgetSettingsState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class WidgetSettingsSaverResilienceTest {
    @Before
    fun setup() {
        initTestLogger()
    }

    @Test
    fun `concurrent save calls wait for each other instead of returning AlreadyInProgress`() =
        runTest {
            val saveStarted = CompletableDeferred<Unit>()
            val canCompleteSave = CompletableDeferred<Unit>()
            var callCount = 0

            val controlledLoader =
                object : WidgetSettingsLoader {
                    override suspend fun load(): WidgetSettingsState = WidgetSettingsState()

                    override suspend fun saveGeneralSettings(state: WidgetSettingsState) {
                        callCount++
                        saveStarted.complete(Unit)
                        canCompleteSave.await()
                    }

                    override suspend fun saveSimSettings(state: WidgetSettingsState): List<Int> = emptyList()

                    override suspend fun save(
                        state: WidgetSettingsState,
                        skipSimSettings: Boolean,
                    ) = WidgetSaveResult.Success
                }

            val saver = WidgetSettingsSaver(controlledLoader, mock())

            // 1. Start first save
            val firstSave =
                async {
                    saver.save(WidgetSettingsState())
                }
            runCurrent()
            saveStarted.await()

            // 2. Start second save while first is pending
            val secondSave =
                async {
                    saver.save(WidgetSettingsState())
                }
            runCurrent()

            // Second save should be suspended waiting for lock
            assertFalse(secondSave.isCompleted)

            // 3. Complete first save
            canCompleteSave.complete(Unit)
            runCurrent()

            // Both should eventually complete successfully
            assertEquals(SaveResult.Success, firstSave.await())
            assertEquals(SaveResult.Success, secondSave.await())
            assertEquals(2, callCount)
        }
}
