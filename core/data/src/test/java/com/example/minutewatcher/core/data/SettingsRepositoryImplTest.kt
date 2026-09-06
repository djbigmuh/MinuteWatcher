package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryImplTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val testScope = TestScope(mainDispatcherRule.testDispatcher)

    private lateinit var dataStore: DataStore<Preferences>

    @Before
    fun setup() {
        dataStore =
            PreferenceDataStoreFactory.create(
                scope = testScope,
            ) { tmpFolder.newFile("test_settings.preferences_pb") }
    }

    @Test
    fun testClearAllCallSessions_RemovesExpectedKeys() =
        runTest {
            val repository = CallSessionRepositoryImpl(dataStore)

            dataStore.edit { prefs ->
                prefs[DataStoreKeys.callStartTimeKey(1)] = 1000L
                prefs[DataStoreKeys.bootTimeKey(1)] = 5000L
                prefs[DataStoreKeys.callIdentityKey(1)] = "id1"

                prefs[DataStoreKeys.callStartTimeKey(2)] = 2000L
                prefs[DataStoreKeys.bootTimeKey(2)] = 6000L

                prefs[DataStoreKeys.APP_THEME] = AppTheme.DARK.value
            }

            repository.clearAllCallSessions()
            advanceUntilIdle()

            val prefs = dataStore.data.first()
            assertNull(prefs[DataStoreKeys.callStartTimeKey(1)])
            assertNull(prefs[DataStoreKeys.bootTimeKey(1)])
            assertNull(prefs[DataStoreKeys.callIdentityKey(1)])
            assertNull(prefs[DataStoreKeys.callStartTimeKey(2)])
            assertNull(prefs[DataStoreKeys.bootTimeKey(2)])

            assertEquals(AppTheme.DARK.value, prefs[DataStoreKeys.APP_THEME])
        }

    @Test
    fun testGetActiveCallSubIds() =
        runTest {
            val repository = CallSessionRepositoryImpl(dataStore)

            dataStore.edit { prefs ->
                prefs[DataStoreKeys.callStartTimeKey(1)] = 1000L
                prefs[DataStoreKeys.callStartTimeKey(3)] = 3000L
                prefs[DataStoreKeys.callStartTimeKey(5)] = 0L
            }

            val activeSubIds = repository.getActiveCallSubIds()
            assertEquals(setOf(1, 3), activeSubIds.toSet())
        }

    @Test
    fun testDefaultValues() =
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
    fun testUpdateAppSettings_AtomicUpdate() =
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
    fun testUpdateAppSettings_PreservesOtherFields() =
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
    fun testUpdateAppSettings_SavesOnboardingCompleted() =
        runTest {
            val repository = AppSettingsRepositoryImpl(dataStore, testScope)

            repository.updateAppSettings { it.copy(onboardingCompleted = true) }
            advanceUntilIdle()

            val settings = repository.getAppSettings().first()
            assertEquals(true, settings.onboardingCompleted)
        }

    @Test
    fun testConcurrentUpdates() =
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

            job1.join()
            job2.join()
            advanceUntilIdle()

            val settings = repository.getAppSettings().first()
            assertEquals(10, settings.widgetTransparency)
            assertEquals(4, settings.widgetFontSize)
        }

    @Test
    fun testFlowEmitsUpdatedValue() =
        runTest {
            val repository = AppSettingsRepositoryImpl(dataStore, testScope)
            val settingsList = mutableListOf<com.example.minutewatcher.core.model.AppSettings>()

            val job =
                launch {
                    repository.getAppSettings().take(2).toList(settingsList)
                }
            testScheduler.advanceUntilIdle()

            repository.updateAppSettings { it.copy(onboardingCompleted = true) }
            advanceUntilIdle()

            job.join()
            assertEquals(2, settingsList.size)
            assertEquals(false, settingsList[0].onboardingCompleted)
            assertEquals(true, settingsList[1].onboardingCompleted)
        }

    @Test
    fun testGetAppSettingsOnce_ReturnsFreshDataWithoutSubscribers() =
        runTest {
            val repository = AppSettingsRepositoryImpl(dataStore, testScope)

            // 1. Initial default value
            assertEquals(false, repository.getAppSettingsOnce().onboardingCompleted)

            // 2. Update directly via DataStore (bypassing repository for pure test)
            dataStore.edit { prefs ->
                prefs[DataStoreKeys.ONBOARDING_COMPLETED] = true
            }

            // 3. getAppSettingsOnce should return the new value
            assertEquals(true, repository.getAppSettingsOnce().onboardingCompleted)
        }

    @Test
    fun testUpdateAppSettings_ThrowsException_DoesNotModifyDataStore() =
        runTest {
            val repository = AppSettingsRepositoryImpl(dataStore, testScope)

            // 1. Initial state
            dataStore.edit { it[DataStoreKeys.APP_THEME] = AppTheme.DARK.value }

            // 2. Attempt update that throws
            try {
                repository.updateAppSettings {
                    // Try to modify theme but then throw
                    throw RuntimeException("Simulated failure")
                }
            } catch (_: Exception) {
            }

            // 3. Verify DataStore is unchanged
            val prefs = dataStore.data.first()
            assertEquals(AppTheme.DARK.value, prefs[DataStoreKeys.APP_THEME])
        }

    @Test
    fun testUpdateAppSettings_RedundantUpdate_DoesNotEmit() =
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
}
