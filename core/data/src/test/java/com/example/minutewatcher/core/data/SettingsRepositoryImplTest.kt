package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.minutewatcher.core.model.AppTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
    val tmpFolder = TemporaryFolder()

    private lateinit var dataStore: DataStore<Preferences>

    @Before
    fun setup() {
        dataStore =
            PreferenceDataStoreFactory.create(
                produceFile = { tmpFolder.newFile("test_settings.preferences_pb") },
            )
    }

    @Test
    fun testClearAllCallSessions_RemovesExpectedKeys() =
        runTest {
            val repository = SettingsRepositoryImpl(dataStore, backgroundScope)

            dataStore.edit { prefs ->
                prefs[SettingsKeys.callStartTimeKey(1)] = 1000L
                prefs[SettingsKeys.bootTimeKey(1)] = 5000L
                prefs[SettingsKeys.callIdentityKey(1)] = "id1"

                prefs[SettingsKeys.callStartTimeKey(2)] = 2000L
                prefs[SettingsKeys.bootTimeKey(2)] = 6000L

                prefs[SettingsKeys.APP_THEME] = AppTheme.DARK.value
            }

            repository.clearAllCallSessions()

            val prefs = dataStore.data.first()
            assertNull(prefs[SettingsKeys.callStartTimeKey(1)])
            assertNull(prefs[SettingsKeys.bootTimeKey(1)])
            assertNull(prefs[SettingsKeys.callIdentityKey(1)])
            assertNull(prefs[SettingsKeys.callStartTimeKey(2)])
            assertNull(prefs[SettingsKeys.bootTimeKey(2)])

            assertEquals(AppTheme.DARK.value, prefs[SettingsKeys.APP_THEME])
        }

    @Test
    fun testGetActiveCallSubIds() =
        runTest {
            val repository = SettingsRepositoryImpl(dataStore, backgroundScope)

            dataStore.edit { prefs ->
                prefs[SettingsKeys.callStartTimeKey(1)] = 1000L
                prefs[SettingsKeys.callStartTimeKey(3)] = 3000L
                prefs[SettingsKeys.callStartTimeKey(5)] = 0L
            }

            val activeSubIds = repository.getActiveCallSubIds()
            assertEquals(setOf(1, 3), activeSubIds.toSet())
        }

    @Test
    fun testUpdateAppSettings_AtomicUpdate() =
        runTest {
            val repository = SettingsRepositoryImpl(dataStore, backgroundScope)

            repository.updateAppSettings { current ->
                current.copy(
                    theme = AppTheme.LIGHT,
                    widgetTransparency = 75,
                )
            }

            val prefs = dataStore.data.first()
            assertEquals(AppTheme.LIGHT.value, prefs[SettingsKeys.APP_THEME])
            assertEquals(75, prefs[SettingsKeys.WIDGET_TRANSPARENCY])
        }

    @Test
    fun testUpdateAppSettings_PreservesOnboardingCompleted() =
        runTest {
            val repository = SettingsRepositoryImpl(dataStore, backgroundScope)

            dataStore.edit { it[SettingsKeys.ONBOARDING_COMPLETED] = true }

            repository.updateAppSettings { current ->
                current.copy(widgetTransparency = 10)
            }

            val prefs = dataStore.data.first()
            assertEquals(true, prefs[SettingsKeys.ONBOARDING_COMPLETED])
            assertEquals(10, prefs[SettingsKeys.WIDGET_TRANSPARENCY])
        }

    @Test
    fun testUpdateAppSettings_SavesOnboardingCompleted() =
        runTest {
            val repository = SettingsRepositoryImpl(dataStore, backgroundScope)

            val initialSettings = repository.getAppSettings().first()
            assertEquals(false, initialSettings.onboardingCompleted)

            repository.updateAppSettings { it.copy(onboardingCompleted = true) }

            val prefs = dataStore.data.first()
            assertEquals(true, prefs[SettingsKeys.ONBOARDING_COMPLETED])

            val finalSettings = repository.getAppSettings().first()
            assertEquals(true, finalSettings.onboardingCompleted)
        }
}
