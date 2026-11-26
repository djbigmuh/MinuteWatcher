package com.github.djbigmuh.minutewatcher.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.github.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SharedPreferencesMigrationTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var context: Context
    private val testScope = TestScope(mainDispatcherRule.testDispatcher)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `migration deletes legacy shared preferences file when all keys are migrated`() =
        runTest {
            val prefsName = "test_prefs"
            val sharedPrefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            sharedPrefs.edit().putInt("test_key", 123).commit()

            val prefsFile = File(context.applicationInfo.dataDir, "shared_prefs/$prefsName.xml")
            assert(prefsFile.exists())

            val dataStore: DataStore<Preferences> =
                PreferenceDataStoreFactory.create(
                    scope = testScope,
                    migrations = listOf(SharedPreferencesMigration(context, prefsName)),
                ) { tmpFolder.newFile("test.preferences_pb") }

            // Trigger migration by reading data
            val prefs = dataStore.data.first()
            assertEquals(123, prefs[intPreferencesKey("test_key")])

            // DataStore 1.2.1 should have deleted the file after successful migration of all keys
            assertFalse("Legacy SharedPreferences file should have been deleted", prefsFile.exists())
        }
}
