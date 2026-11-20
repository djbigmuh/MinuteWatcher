package com.github.djbigmuh.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.github.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import com.github.djbigmuh.minutewatcher.core.data.di.DataStoreKeys
import com.github.djbigmuh.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AppSettingsRepositoryResilienceTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val testScope = TestScope(mainDispatcherRule.testDispatcher)

    @Test
    fun `appSettingsState recovers after DataStore error`() =
        runTest {
            val realDataStore =
                PreferenceDataStoreFactory.create(
                    scope = testScope,
                ) { tmpFolder.newFile("test_settings.preferences_pb") }

            var throwError = false
            val failingDataStore =
                object : DataStore<Preferences> by realDataStore {
                    override val data =
                        flow {
                            realDataStore.data.collect {
                                if (throwError) {
                                    throw IOException("Simulated DataStore error")
                                }
                                emit(it)
                            }
                        }
                }

            val repository = AppSettingsRepositoryImpl(failingDataStore, testScope)

            // 1. Initial successful read
            assertEquals(2, repository.observeAppSettings().value.widgetFontSize)

            // 2. Trigger error
            throwError = true
            // DataStore flow in repository should fail, emit defaults and start waiting
            runCurrent()

            // Advance time to trigger recovery attempt
            advanceTimeBy(2.seconds)
            runCurrent()

            // 3. Fix DataStore and update value
            throwError = false
            realDataStore.edit { it[DataStoreKeys.WIDGET_FONT_SIZE] = 4 }

            // Wait for flow to restart and emit new value
            advanceTimeBy(2.seconds)
            runCurrent()

            assertEquals(4, repository.observeAppSettings().value.widgetFontSize)
        }

    @Test
    fun `appSettingsState continues retrying even after many consecutive errors`() =
        runTest {
            val realDataStore =
                PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                ) { tmpFolder.newFile("test_settings_2.preferences_pb") }

            var throwError = true
            val failingDataStore =
                object : DataStore<Preferences> by realDataStore {
                    override val data =
                        flow {
                            realDataStore.data.collect {
                                if (throwError) {
                                    throw IOException("Simulated DataStore error")
                                }
                                emit(it)
                            }
                        }
                }

            val repository = AppSettingsRepositoryImpl(failingDataStore, backgroundScope)

            // Initial value should be default
            assertEquals(2, repository.observeAppSettings().value.widgetFontSize)

            // Advance time significantly to pass previous limit (10 errors)
            // 1s, 2s, 4s, 8s, 16s, 30s... x 15
            advanceTimeBy(600.seconds)
            runCurrent()

            // Status should be Error
            val status = repository.status.value
            assertTrue("Status should be Error after many failures", status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorType.DATABASE_ERROR, (status as TrackingStatus.Error).type)

            // Now fix it
            throwError = false
            realDataStore.edit { it[DataStoreKeys.WIDGET_FONT_SIZE] = 4 }

            // Wait for recovery
            advanceTimeBy(60.seconds)
            runCurrent()

            // New value should be delivered
            assertEquals(4, repository.observeAppSettings().value.widgetFontSize)
            // Status should recover to Active
            assertEquals(TrackingStatus.Active, repository.status.value)
        }

    @Test
    fun `retry delay remains bounded at maximum delay during long persistent failures`() =
        runTest {
            val realDataStore =
                PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                ) { tmpFolder.newFile("test_settings_3.preferences_pb") }

            var attemptCount = 0
            val failingDataStore =
                object : DataStore<Preferences> by realDataStore {
                    override val data =
                        flow<Preferences> {
                            attemptCount++
                            throw IOException("Simulated persistent DataStore error")
                        }
                }

            val repository = AppSettingsRepositoryImpl(failingDataStore, backgroundScope)
            repository.observeAppSettings()
            runCurrent()

            var stepCount = 0
            while ((attemptCount < 31) && (stepCount < 1000)) {
                advanceTimeBy(1.seconds)
                runCurrent()
                stepCount++
            }
            assertEquals(31, attemptCount)

            val attemptsBeforeWindow = attemptCount
            advanceTimeBy(61.seconds)
            runCurrent()

            val newAttempts = attemptCount - attemptsBeforeWindow
            assertEquals(2, newAttempts)
        }
}
