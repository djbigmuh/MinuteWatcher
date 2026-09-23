package com.example.minutewatcher.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.minutewatcher.core.common.util.FixedTimeProvider
import com.example.minutewatcher.core.common.util.initTestLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SimUsageRepositoryConcurrencyTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val timeProvider = FixedTimeProvider(currentTime = 1000000000000L)

    @Before
    fun setup() {
        initTestLogger()
    }

    @Test
    fun `resetSimUsageIfDue handles concurrent calls by performing reset only once`() =
        runTest {
            // 1. Generate unique directory and file name.
            // Using a separate directory ensures isolation from other tests
            // and file system stability (especially on Windows).
            val dir = tmpFolder.newFolder()
            val uniqueName = "test_concurrency_${UUID.randomUUID()}.preferences_pb"
            val file = File(dir, uniqueName)

            // 2. Create STRICTLY ONE instance of DataStore and Repository
            // Use backgroundScope for correct wait of DataStore background tasks
            val dataStore =
                PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                ) {
                    file
                }
            val repository = SimUsageRepositoryImpl(dataStore, timeProvider, SimUsageMapper(), SimUsageResetLogic())

            val identity = "test_sim"
            val autoResetDay = 1
            val now = 1000000000000L

            // Data preparation
            repository.setLastResetDate(identity, 1L)
            repository.setMinutesUsed(identity, 50)

            // 3. Launch concurrent calls on the VERY SAME repository instance
            val concurrentCalls = 10
            val results =
                (1..concurrentCalls)
                    .map {
                        async {
                            repository.resetSimUsageIfDue(identity, autoResetDay, now)
                        }
                    }.awaitAll()

            // Give DataStore time to complete background file renaming operations
            testScheduler.advanceUntilIdle()

            val resetCount = results.count { it }
            assertEquals("Should only perform reset once", 1, resetCount)

            val usage = repository.getSimUsage(identity).first()
            assertEquals(0, usage.minutesUsed)
            assertEquals(now, usage.lastResetDate)
        }
}
