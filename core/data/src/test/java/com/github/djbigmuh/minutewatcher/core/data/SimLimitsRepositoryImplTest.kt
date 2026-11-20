package com.github.djbigmuh.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.github.djbigmuh.minutewatcher.core.common.testing.InMemoryPreferencesDataStore
import com.github.djbigmuh.minutewatcher.core.common.util.FixedTimeProvider
import com.github.djbigmuh.minutewatcher.core.common.util.initTestLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimLimitsRepositoryImplTest {
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: SimLimitsRepositoryImpl

    @Before
    fun setup() {
        initTestLogger()
        dataStore = InMemoryPreferencesDataStore()
        repository = SimLimitsRepositoryImpl(dataStore)
    }

    @Test
    fun `setAutoReset coerces day = 45 to 31`() =
        runTest {
            repository.setAutoReset("sim_0", day = 45, enabled = true)

            val autoResetDay = repository.getAutoResetDay("sim_0").first()
            assertEquals(31, autoResetDay)
        }

    @Test
    fun `setAutoReset coerces day = 0 to 1`() =
        runTest {
            repository.setAutoReset("sim_0", day = 0, enabled = true)

            val autoResetDay = repository.getAutoResetDay("sim_0").first()
            assertEquals(1, autoResetDay)
        }

    @Test
    fun `setAutoReset preserves day = 15`() =
        runTest {
            repository.setAutoReset("sim_0", day = 15, enabled = true)

            val autoResetDay = repository.getAutoResetDay("sim_0").first()
            assertEquals(15, autoResetDay)
        }

    @Test
    fun `update SIM limits and get usage`() =
        runTest {
            val usageRepo =
                SimUsageRepositoryImpl(
                    dataStore = dataStore,
                    timeProvider = FixedTimeProvider(currentTime = 1000000L),
                    mapper = SimUsageMapper(),
                    resetLogic = SimUsageResetLogic(),
                )
            val identity = "test_sim"

            repository.updateSimLimits(identity, 100, 1024)

            val usage = usageRepo.getSimUsage(identity).first()
            assertEquals(100, usage.minutesLimit)
            assertEquals(1024, usage.trafficLimitMb)
        }
}
