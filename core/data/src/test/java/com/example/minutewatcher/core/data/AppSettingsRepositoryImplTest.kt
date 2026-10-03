package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.minutewatcher.core.common.testing.InMemoryPreferencesDataStore
import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.data.di.DataStoreKeys
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.AppTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AppSettingsRepositoryImplTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val testScope = TestScope(mainDispatcherRule.testDispatcher)

    private lateinit var dataStore: DataStore<Preferences>

    @Before
    fun setup() {
        dataStore = InMemoryPreferencesDataStore()
    }

    @Test
    fun `default values`() =
        runTest {
            val repository = AppSettingsRepositoryImpl(dataStore, testScope)
            val settings = repository.getAppSettings().first()

            assertEquals(AppTheme.FOLLOW_SYSTEM, settings.theme)
            assertEquals(true, settings.notifyLowMinutes)
            assertEquals(true, settings.notifyLowTraffic)
            assertEquals(2, settings.widgetLayoutMode)
            assertEquals(50, settings.widgetTransparency)
            assertEquals(false, settings.onboardingCompleted)
        }

    @Test
    fun `update app settings atomic update`() =
        runTest {
            val repository = AppSettingsRepositoryImpl(dataStore, testScope)

            repository.updateAppSettings { current ->
                current.copy(
                    theme = AppTheme.LIGHT,
                    widgetTransparency = 75,
                )
            }
            advanceUntilIdle()

            val prefs = dataStore.data.first()
            assertEquals(AppTheme.LIGHT.value, prefs[DataStoreKeys.APP_THEME])
            assertEquals(75, prefs[DataStoreKeys.WIDGET_TRANSPARENCY])
        }

    @Test
    fun `update app settings preserves other fields`() =
        runTest {
            val repository = AppSettingsRepositoryImpl(dataStore, testScope)

            dataStore.edit {
                it[DataStoreKeys.ONBOARDING_COMPLETED] = true
                it[DataStoreKeys.APP_THEME] = AppTheme.DARK.value
            }

            repository.updateAppSettings { current ->
                current.copy(widgetTransparency = 10)
            }
            advanceUntilIdle()

            val prefs = dataStore.data.first()
            assertEquals(true, prefs[DataStoreKeys.ONBOARDING_COMPLETED])
            assertEquals(AppTheme.DARK.value, prefs[DataStoreKeys.APP_THEME])
            assertEquals(10, prefs[DataStoreKeys.WIDGET_TRANSPARENCY])
        }

    @Test
    fun `update app settings saves onboarding completed`() =
        runTest {
            val repository = AppSettingsRepositoryImpl(dataStore, testScope)

            repository.updateAppSettings { it.copy(onboardingCompleted = true) }
            advanceUntilIdle()

            val settings = repository.getAppSettings().first()
            assertEquals(true, settings.onboardingCompleted)
        }

    @Test
    fun `concurrent updates`() =
        runTest {
            val repository = AppSettingsRepositoryImpl(dataStore, testScope)

            val job1 =
                launch {
                    repository.updateAppSettings { it.copy(widgetTransparency = 10) }
                }
            val job2 =
                launch {
                    repository.updateAppSettings { it.copy(widgetFontSize = 4) }
                }

            withTimeoutOrNull(30.seconds) { job1.join() }
                ?: throw AssertionError("Timed out waiting for job1")
            withTimeoutOrNull(30.seconds) { job2.join() }
                ?: throw AssertionError("Timed out waiting for job2")
            advanceUntilIdle()

            val settings = repository.getAppSettings().first()
            assertEquals(10, settings.widgetTransparency)
            assertEquals(4, settings.widgetFontSize)
        }

    @Test
    fun `flow emits updated value`() =
        runTest {
            val repository = AppSettingsRepositoryImpl(dataStore, testScope)
            val settingsList = mutableListOf<AppSettings>()

            val job =
                launch {
                    repository.getAppSettings().take(2).toList(settingsList)
                }
            testScheduler.advanceUntilIdle()

            repository.updateAppSettings { it.copy(onboardingCompleted = true) }
            advanceUntilIdle()

            withTimeoutOrNull(30.seconds) { job.join() }
                ?: throw AssertionError("Timed out waiting for job")
            assertEquals(2, settingsList.size)
            assertEquals(false, settingsList[0].onboardingCompleted)
            assertEquals(true, settingsList[1].onboardingCompleted)
        }

    @Test
    fun `get app settings returns fresh data without subscribers`() =
        runTest {
            val repository = AppSettingsRepositoryImpl(dataStore, backgroundScope)

            // 1. Initial default value
            assertEquals(false, repository.getAppSettings().first().onboardingCompleted)

            // 2. Update directly via DataStore (bypassing repository for pure test)
            dataStore.edit { prefs ->
                prefs[DataStoreKeys.ONBOARDING_COMPLETED] = true
            }

            // 3. getAppSettings().first() should return the new value
            assertEquals(true, repository.getAppSettings().first().onboardingCompleted)
        }

    @Test
    fun `update app settings throws exception does not modify DataStore`() =
        runTest {
            val repository = AppSettingsRepositoryImpl(dataStore, testScope)

            // 1. Initial state
            dataStore.edit { it[DataStoreKeys.APP_THEME] = AppTheme.DARK.value }

            // 2. Attempt update that throws
            try {
                repository.updateAppSettings {
                    // Try to modify theme but then throw
                    throw IllegalStateException("Simulated failure")
                }
            } catch (_: Exception) {
            }

            // 3. Verify DataStore is unchanged
            val prefs = dataStore.data.first()
            assertEquals(AppTheme.DARK.value, prefs[DataStoreKeys.APP_THEME])
        }

    @Test
    fun `update app settings redundant update does not emit`() =
        runTest {
            val repository = AppSettingsRepositoryImpl(dataStore, testScope)
            val settingsList = mutableListOf<AppSettings>()

            // Start collecting
            val job =
                launch {
                    repository.getAppSettings().toList(settingsList)
                }
            testScheduler.advanceUntilIdle() // Initial emission

            // Perform update with same values
            repository.updateAppSettings { it } // No change
            testScheduler.advanceUntilIdle()

            // Perform update with different values
            repository.updateAppSettings { it.copy(onboardingCompleted = true) }
            testScheduler.advanceUntilIdle()

            job.cancel()
            // Expected: 1 (initial) + 1 (different) = 2.
            // The redundant one should not add to the list.
            assertEquals(2, settingsList.size)
            assertEquals(false, settingsList[0].onboardingCompleted)
            assertEquals(true, settingsList[1].onboardingCompleted)
        }

    @Test
    fun `get app settings suspends until loaded`() =
        runTest {
            val repository = AppSettingsRepositoryImpl(dataStore, backgroundScope)
            val settingsList = mutableListOf<AppSettings>()

            val job =
                launch {
                    repository.getAppSettings().toList(settingsList)
                }
            advanceUntilIdle()

            // Initial state AppSettings() is filtered by isLoaded=false
            // Only loaded settings from DataStore should be present
            assertTrue(settingsList.isNotEmpty())
            assertTrue(settingsList.all { it.isLoaded })

            job.cancel()
        }

    @Test
    fun `get app settings filters not loaded`() =
        runTest {
            val repository = AppSettingsRepositoryImpl(dataStore, backgroundScope)
            val settingsList = mutableListOf<AppSettings>()

            // 1. Start collecting
            val job =
                launch {
                    repository.getAppSettings().toList(settingsList)
                }
            advanceUntilIdle()

            // At this point, it might have emitted the first DataStore value (empty prefs)
            // or nothing if DataStore is slow.
            // Crucially, ANY emitted value must have isLoaded = true.
            assertTrue(settingsList.all { it.isLoaded })

            // 2. Trigger another emission
            repository.updateAppSettings { it.copy(onboardingCompleted = true) }
            advanceUntilIdle()

            assertTrue(settingsList.isNotEmpty())
            assertTrue(settingsList.last().isLoaded)
            assertTrue(settingsList.last().onboardingCompleted)

            job.cancel()
        }

    @Test
    fun `observe app settings does not filter`() =
        runTest {
            val repository = AppSettingsRepositoryImpl(dataStore, testScope)
            // observeAppSettings() returns appSettingsState, which is a StateFlow
            // We just ensure we can get a value and it eventually becomes loaded
            advanceUntilIdle()
            val loaded = repository.observeAppSettings().value
            assertEquals(true, loaded.isLoaded)
        }
}
