package com.example.minutewatcher.feature.settings.ui

import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.domain.WidgetSettingsLoader
import com.example.minutewatcher.core.model.WidgetSettingsState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import kotlin.time.Duration.Companion.seconds

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
                        withTimeoutOrNull(30.seconds) { canCompleteSave.await() }
                            ?: throw AssertionError("Timed out waiting for canCompleteSave in controlledLoader")
                    }

                    override suspend fun saveSimSettings(state: WidgetSettingsState): List<Int> = emptyList()
                }

            val saver = WidgetSettingsSaver(controlledLoader, mock())

            // 1. Start first save
            val firstSave =
                async {
                    saver.save(WidgetSettingsState())
                }
            runCurrent()
            withTimeoutOrNull(30.seconds) { saveStarted.await() }
                ?: throw AssertionError("Timed out waiting for saveStarted")

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
            val res1 =
                withTimeoutOrNull(30.seconds) { firstSave.await() }
                    ?: throw AssertionError("Timed out waiting for firstSave")
            val res2 =
                withTimeoutOrNull(30.seconds) { secondSave.await() }
                    ?: throw AssertionError("Timed out waiting for secondSave")
            assertEquals(SaveResult.Success, res1)
            assertEquals(SaveResult.Success, res2)
            assertEquals(2, callCount)
        }
}
