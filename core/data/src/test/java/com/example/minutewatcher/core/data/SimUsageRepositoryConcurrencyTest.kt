package com.example.minutewatcher.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.FixedTimeProvider
import com.example.minutewatcher.core.common.util.NoOpLogger
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
import org.mockito.kotlin.mock
import java.io.File
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class SimUsageRepositoryConcurrencyTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val timeProvider = FixedTimeProvider(currentTime = 2000000L)

    @Before
    fun setup() {
        AppLogger.init(mock(), NoOpLogger())
    }

    @Test
    fun `resetSimUsageIfDue handles concurrent calls by performing reset only once`() =
        runTest {
            val uniqueName = "test_concurrency_${UUID.randomUUID()}.preferences_pb"
            val file = File(tmpFolder.root, uniqueName)
            val dataStore =
                PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { file },
                )
            val repository = SimUsageRepositoryImpl(dataStore, timeProvider)

            val identity = "test_sim"
            val autoResetDay = 1
            val now = 2000000L

            repository.setLastResetDate(identity, 0L)
            repository.setMinutesUsed(identity, 50)

            val concurrentCalls = 10
            val results =
                (1..concurrentCalls)
                    .map {
                        async {
                            repository.resetSimUsageIfDue(identity, autoResetDay, now)
                        }
                    }.awaitAll()

            val resetCount = results.count { it }
            assertEquals("Should only perform reset once", 1, resetCount)

            val usage = repository.getSimUsage(identity).first()
            assertEquals(0, usage.minutesUsed)
            assertEquals(now, usage.lastResetDate)
        }
}
