package com.github.djbigmuh.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.github.djbigmuh.minutewatcher.core.common.testing.InMemoryPreferencesDataStore
import com.github.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import com.github.djbigmuh.minutewatcher.core.data.di.DataStoreKeys
import com.github.djbigmuh.minutewatcher.core.model.AppSettings
import com.github.djbigmuh.minutewatcher.core.model.AppTheme
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
import org.junit.Assert.assertFalse
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
            assertTrue(settings.notifyLowMinutes)
            assertTrue(settings.notifyLowTraffic)
            assertFalse(settings.aodSummaryEnabled)
            assertFalse(settings.assistantAccessEnabled)
            assertEquals(2, settings.widgetLayoutMode)
            assertEquals(50, settings.widgetTransparency)
            assertFalse(settings.onboardingCompleted)
            assertFalse(settings.swipeHintDismissed)
        }

    @Test
    fun `update app settings atomic update`() =
        runTest {
            val repository = AppSettingsRepositoryImpl(dataStore, testScope)

            repository.updateAppSettings { current ->
                current.copy(
                    theme = AppTheme.DARK,
                    widgetTransparency = 10,
                )
            }
            advanceUntilIdle()

            val prefs = dataStore.data.first()
            assertFalse(prefs[DataStoreKeys.ONBOARDING_COMPLETED] == true)
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
            assertTrue(settings.onboardingCompleted)
        }

    @Test
    fun `update app settings saves swipe hint dismissed`() =
        runTest {
            val repository = AppSettingsRepositoryImpl(dataStore, testScope)

            repository.updateAppSettings { it.copy(swipeHintDismissed = true) }
            advanceUntilIdle()

            val settings = repository.getAppSettings().first()
            assertTrue(settings.swipeHintDismissed)
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
            assertFalse(settingsList[0].onboardingCompleted)
            assertTrue(settingsList[1].onboardingCompleted)
        }

    @Test
    fun `get app settings returns fresh data without subscribers`() =
        runTest {
            val repository = AppSettingsRepositoryImpl(dataStore, backgroundScope)

            // 1. Initial default value
            assertFalse(repository.getAppSettings().first().onboardingCompleted)

            // 2. Update directly via DataStore (bypassing repository for pure test)
            dataStore.edit { prefs ->
                prefs[DataStoreKeys.ONBOARDING_COMPLETED] = true
            }

            // 3. getAppSettings().first() should return the new value
            assertTrue(repository.getAppSettings().first().onboardingCompleted)
        }

    @Test
    fun `update app settings throws exception does not modify DataStore`() =
        runTest {
            val repository = AppSettingsRepositoryImpl(dataStore, testScope)

            // 1. Initial state
            val initialSettings = repository.getAppSettings().first()
            assertFalse(initialSettings.onboardingCompleted)

            // 2. Perform update that throws exception
            try {
                repository.updateAppSettings {
                    throw RuntimeException("Simulated error during update")
                }
            } catch (_: Exception) {
                // Expected
            }
            advanceUntilIdle()

            // 3. Verify DataStore remains unchanged
            val settingsAfterError = repository.getAppSettings().first()
            assertFalse(settingsAfterError.onboardingCompleted)
        }

    @Test
    fun `update app settings distinct until changed`() =
        runTest {
            val repository = AppSettingsRepositoryImpl(dataStore, testScope)
            val settingsList = mutableListOf<AppSettings>()

            val job =
                launch {
                    repository.getAppSettings().toList(settingsList)
                }
            testScheduler.advanceUntilIdle()

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
            assertFalse(settingsList[0].onboardingCompleted)
            assertTrue(settingsList[1].onboardingCompleted)
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
            assertTrue(loaded.isLoaded)
        }

    @Test
    fun `update app settings roundtrip permanently denied flags`() =
        runTest {
            val repository = AppSettingsRepositoryImpl(dataStore, testScope)

            repository.updateAppSettings {
                it.copy(
                    phonePermissionPermanentlyDenied = true,
                    notificationPermissionPermanentlyDenied = true,
                )
            }
            advanceUntilIdle()

            val settings = repository.getAppSettings().first()
            assertTrue(settings.phonePermissionPermanentlyDenied)
            assertTrue(settings.notificationPermissionPermanentlyDenied)
        }

    @Test
    fun `update app settings roundtrip aod summary enabled`() =
        runTest {
            val repository = AppSettingsRepositoryImpl(dataStore, testScope)

            // Default value when key is missing is false
            val defaultSettings = repository.getAppSettings().first()
            assertFalse(defaultSettings.aodSummaryEnabled)

            repository.updateAppSettings {
                it.copy(aodSummaryEnabled = true)
            }
            advanceUntilIdle()

            val updatedSettings = repository.getAppSettings().first()
            assertTrue(updatedSettings.aodSummaryEnabled)
            assertTrue(dataStore.data.first()[DataStoreKeys.AOD_SUMMARY_ENABLED] == true)
        }

    @Test
    fun `update app settings roundtrip assistant access enabled`() =
        runTest {
            val repository = AppSettingsRepositoryImpl(dataStore, testScope)

            val defaultSettings = repository.getAppSettings().first()
            assertFalse(defaultSettings.assistantAccessEnabled)

            repository.updateAppSettings {
                it.copy(assistantAccessEnabled = true)
            }
            advanceUntilIdle()

            val updatedSettings = repository.getAppSettings().first()
            assertTrue(updatedSettings.assistantAccessEnabled)
            assertTrue(dataStore.data.first()[DataStoreKeys.ASSISTANT_ACCESS_ENABLED] == true)

            repository.updateAppSettings {
                it.copy(assistantAccessEnabled = false)
            }
            advanceUntilIdle()

            val finalSettings = repository.getAppSettings().first()
            assertFalse(finalSettings.assistantAccessEnabled)
            assertFalse(dataStore.data.first()[DataStoreKeys.ASSISTANT_ACCESS_ENABLED] == true)
        }

    @Test
    fun `update app settings roundtrip usage access dialog shown`() =
        runTest {
            val repository = AppSettingsRepositoryImpl(dataStore, testScope)

            val defaultSettings = repository.getAppSettings().first()
            assertFalse(defaultSettings.usageAccessDialogShown)

            repository.updateAppSettings {
                it.copy(usageAccessDialogShown = true)
            }
            advanceUntilIdle()

            val updatedSettings = repository.getAppSettings().first()
            assertTrue(updatedSettings.usageAccessDialogShown)
            assertTrue(dataStore.data.first()[DataStoreKeys.USAGE_ACCESS_DIALOG_SHOWN] == true)
        }
}
