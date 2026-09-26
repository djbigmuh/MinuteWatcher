package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.example.minutewatcher.core.common.testing.InMemoryPreferencesDataStore
import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.common.util.initTestLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AutoResetScheduleRepositoryImplTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: AutoResetScheduleRepositoryImpl

    @Before
    fun setup() {
        initTestLogger()
        dataStore = InMemoryPreferencesDataStore()
        repository = AutoResetScheduleRepositoryImpl(dataStore)
    }

    @Test
    fun `getLastCheckMillis returns null when not set`() =
        runTest {
            assertNull(repository.getLastCheckMillis())
        }

    @Test
    fun `getFailureCount returns zero when not set`() =
        runTest {
            assertEquals(0, repository.getFailureCount())
        }

    @Test
    fun `setLastCheckMillis and setFailureCount write and read back values`() =
        runTest {
            val now = 1700000000000L
            repository.setLastCheckMillis(now)
            repository.setFailureCount(2)

            assertEquals(now, repository.getLastCheckMillis())
            assertEquals(2, repository.getFailureCount())
        }
}
