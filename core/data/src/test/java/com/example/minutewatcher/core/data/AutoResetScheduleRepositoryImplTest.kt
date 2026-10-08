package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.example.minutewatcher.core.common.testing.InMemoryPreferencesDataStore
import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.data.util.DataStoreWriteTimeoutException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AutoResetScheduleRepositoryImplTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: AutoResetScheduleRepositoryImpl

    private class HangingDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = MutableSharedFlow()

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            awaitCancellation()
        }
    }

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

    @Test
    fun `setLastCheckMillis throws DataStoreWriteTimeoutException on write timeout`() =
        runTest {
            val repo = AutoResetScheduleRepositoryImpl(HangingDataStore())

            val result =
                runCatching {
                    withTimeout(10.seconds) {
                        repo.setLastCheckMillis(1000L)
                    }
                }

            val exception = result.exceptionOrNull()
            assertTrue(
                "Expected DataStoreWriteTimeoutException, but was $exception",
                exception is DataStoreWriteTimeoutException
            )
            assertEquals("DataStore write timeout (setLastCheckMillis)", exception?.message)
        }

    @Test
    fun `setFailureCount throws DataStoreWriteTimeoutException on write timeout`() =
        runTest {
            val repo = AutoResetScheduleRepositoryImpl(HangingDataStore())

            val result =
                runCatching {
                    withTimeout(10.seconds) {
                        repo.setFailureCount(3)
                    }
                }

            val exception = result.exceptionOrNull()
            assertTrue(
                "Expected DataStoreWriteTimeoutException, but was $exception",
                exception is DataStoreWriteTimeoutException
            )
            assertEquals("DataStore write timeout (setFailureCount)", exception?.message)
        }
}
