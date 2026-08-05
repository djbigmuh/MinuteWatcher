package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.FixedTimeProvider
import com.example.minutewatcher.core.common.util.NoOpLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock

@OptIn(ExperimentalCoroutinesApi::class)
class SimUsageRepositoryConcurrencyTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: SimUsageRepositoryImpl
    private val timeProvider = FixedTimeProvider(currentTime = 2000000L)

    @Before
    fun setup() {
        AppLogger.init(mock(), NoOpLogger())
    }

    @Test
    fun `resetSimUsageIfDue handles concurrent calls by performing reset only once`() =
        runTest {
            dataStore =
                PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { tmpFolder.newFile("test_concurrency_settings.preferences_pb") },
                )
            repository = SimUsageRepositoryImpl(dataStore, timeProvider)

            val identity = "test_sim"
            val autoResetDay = 1
            // Billing period starts at 1000000L (simplified)
            val now = 2000000L

            // Initial state: lastResetDate is 0 (long ago)
            repository.setLastResetDate(identity, 0L)
            repository.setMinutesUsed(identity, 50)

            // Launch multiple concurrent reset attempts
            val concurrentCalls = 10
            val resetResults =
                (1..concurrentCalls).map {
                    async {
                        repository.resetSimUsageIfDue(identity, autoResetDay, now)
                    }
                }

            val results = resetResults.map { it.await() }

            // Assert: only one call should have returned true (did reset)
            val resetCount = results.count { it }
            assertEquals("Should only perform reset once", 1, resetCount)

            // Verify final state
            val usage = repository.getSimUsage(identity).first()
            assertEquals(0, usage.minutesUsed)
            assertEquals(now, usage.lastResetDate)
        }
}
