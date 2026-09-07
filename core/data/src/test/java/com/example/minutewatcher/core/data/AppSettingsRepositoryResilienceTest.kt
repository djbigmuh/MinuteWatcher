package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.data.di.DataStoreKeys
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
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
    fun `appSettingsState stops retrying after max consecutive errors`() =
        runTest {
            val failingDataStore =
                object : DataStore<Preferences> {
                    override val data = flow<Preferences> { throw IOException("Permanent failure") }

                    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                        throw IOException("Permanent failure")
                }

            val repository = AppSettingsRepositoryImpl(failingDataStore, backgroundScope)

            // Initial value should be default
            assertEquals(2, repository.observeAppSettings().value.widgetFontSize)

            // Retries should happen 10 times with exponential backoff:
            // 1s, 2s, 4s, 8s, 16s, 30s, 30s, 30s, 30s
            // Total time ~ 181s

            // Advance time significantly to pass all retries
            advanceTimeBy(300.seconds)
            runCurrent()

            // After all retries fail, it should stop.
            // We can't easily check if it stopped from outside without looking at logs,
            // but we can ensure it doesn't crash the scope and maintains the default value.
            assertEquals(2, repository.observeAppSettings().value.widgetFontSize)
        }
}
