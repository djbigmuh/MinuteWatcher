package com.example.minutewatcher.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.FixedTimeProvider
import com.example.minutewatcher.core.common.util.NoOpLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
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

    private val timeProvider = FixedTimeProvider(currentTime = 2000000L)
    private var dsScope: CoroutineScope? = null

    @Before
    fun setup() {
        AppLogger.init(mock(), NoOpLogger())
    }

    @After
    fun tearDown() {
        dsScope?.cancel()
    }

    @Test
    fun `resetSimUsageIfDue handles concurrent calls by performing reset only once`() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = UnconfinedTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        dsScope = scope

        runTest(scheduler) {
            val dataStore =
                PreferenceDataStoreFactory.create(
                    scope = scope,
                    produceFile = {
                        tmpFolder.newFile("test_concurrency.preferences_pb")
                    },
                )
            val repository = SimUsageRepositoryImpl(dataStore, timeProvider)

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

            // Ensure all DataStore operations are completed
            advanceUntilIdle()

            // Assert: only one call should have returned true (did reset)
            val resetCount = results.count { it }
            assertEquals("Should only perform reset once", 1, resetCount)

            // Verify final state
            val usage = repository.getSimUsage(identity).first()
            assertEquals(0, usage.minutesUsed)
            assertEquals(now, usage.lastResetDate)
        }
    }
}
