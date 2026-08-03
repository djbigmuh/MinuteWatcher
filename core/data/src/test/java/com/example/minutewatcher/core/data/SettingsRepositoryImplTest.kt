package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.minutewatcher.core.model.AppTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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
            val repository = CallSessionRepositoryImpl(dataStore)

            dataStore.edit { prefs ->
                prefs[DynamicSettingsKeys.callStartTimeKey(1)] = 1000L
                prefs[DynamicSettingsKeys.bootTimeKey(1)] = 5000L
                prefs[DynamicSettingsKeys.callIdentityKey(1)] = "id1"

                prefs[DynamicSettingsKeys.callStartTimeKey(2)] = 2000L
                prefs[DynamicSettingsKeys.bootTimeKey(2)] = 6000L

                prefs[SettingsKeys.APP_THEME] = AppTheme.DARK.value
            }

            repository.clearAllCallSessions()

            val prefs = dataStore.data.first()
            assertNull(prefs[DynamicSettingsKeys.callStartTimeKey(1)])
            assertNull(prefs[DynamicSettingsKeys.bootTimeKey(1)])
            assertNull(prefs[DynamicSettingsKeys.callIdentityKey(1)])
            assertNull(prefs[DynamicSettingsKeys.callStartTimeKey(2)])
            assertNull(prefs[DynamicSettingsKeys.bootTimeKey(2)])

            assertEquals(AppTheme.DARK.value, prefs[SettingsKeys.APP_THEME])
        }

    @Test
    fun testGetActiveCallSubIds() =
        runTest {
            val repository = CallSessionRepositoryImpl(dataStore)

            dataStore.edit { prefs ->
                prefs[DynamicSettingsKeys.callStartTimeKey(1)] = 1000L
                prefs[DynamicSettingsKeys.callStartTimeKey(3)] = 3000L
                prefs[DynamicSettingsKeys.callStartTimeKey(5)] = 0L
            }

            val activeSubIds = repository.getActiveCallSubIds()
            assertEquals(setOf(1, 3), activeSubIds.toSet())
        }

    @Test
    fun testDefaultValues() =
        runTest {
            val repository = AppSettingsRepositoryImpl(dataStore, backgroundScope)
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
            val repository = AppSettingsRepositoryImpl(dataStore, backgroundScope)

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
    fun testUpdateAppSettings_PreservesOtherFields() =
        runTest {
            val repository = AppSettingsRepositoryImpl(dataStore, backgroundScope)

            dataStore.edit {
                it[SettingsKeys.ONBOARDING_COMPLETED] = true
                it[SettingsKeys.APP_THEME] = AppTheme.DARK.value
            }

            repository.updateAppSettings { current ->
                current.copy(widgetTransparency = 10)
            }

            val prefs = dataStore.data.first()
            assertEquals(true, prefs[SettingsKeys.ONBOARDING_COMPLETED])
            assertEquals(AppTheme.DARK.value, prefs[SettingsKeys.APP_THEME])
            assertEquals(10, prefs[SettingsKeys.WIDGET_TRANSPARENCY])
        }

    @Test
    fun testUpdateAppSettings_SavesOnboardingCompleted() =
        runTest {
            val repository = AppSettingsRepositoryImpl(dataStore, backgroundScope)

            repository.updateAppSettings { it.copy(onboardingCompleted = true) }

            val settings = repository.getAppSettings().first()
            assertEquals(true, settings.onboardingCompleted)
        }

    @Test
    fun testConcurrentUpdates() =
        runTest {
            val repository = AppSettingsRepositoryImpl(dataStore, backgroundScope)

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

            val settings = repository.getAppSettings().first()
            assertEquals(10, settings.widgetTransparency)
            assertEquals(4, settings.widgetFontSize)
        }

    @Test
    fun testFlowEmitsUpdatedValue() =
        runTest {
            val repository = AppSettingsRepositoryImpl(dataStore, backgroundScope)
            val settingsList = mutableListOf<com.example.minutewatcher.core.model.AppSettings>()

            val job =
                launch {
                    repository.getAppSettings().take(2).toList(settingsList)
                }

            repository.updateAppSettings { it.copy(onboardingCompleted = true) }

            job.join()
            assertEquals(2, settingsList.size)
            assertEquals(false, settingsList[0].onboardingCompleted)
            assertEquals(true, settingsList[1].onboardingCompleted)
        }
}
