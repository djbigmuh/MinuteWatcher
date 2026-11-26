package com.github.djbigmuh.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.github.djbigmuh.minutewatcher.core.common.testing.InMemoryPreferencesDataStore
import com.github.djbigmuh.minutewatcher.core.common.util.initTestLogger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

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
    fun `update SIM limits resets notification flags`() =
        runTest {
            val flagsRepo = SimNotificationFlagsRepositoryImpl(dataStore)
            val identity = "test_sim"

            flagsRepo.setNotifiedLowMinutes(identity, true)
            flagsRepo.setNotifiedLowTraffic(identity, true)
            flagsRepo.setNotifiedCriticalMinutes(identity, true)
            flagsRepo.setNotifiedCriticalTraffic(identity, true)
            flagsRepo.setNotifiedExhaustedMinutes(identity, true)
            flagsRepo.setNotifiedExhaustedTraffic(identity, true)

            repository.updateSimLimits(identity, 100, 1024)

            assertEquals(false, flagsRepo.getNotifiedLowMinutes(identity).first())
            assertEquals(false, flagsRepo.getNotifiedLowTraffic(identity).first())
            assertEquals(false, flagsRepo.getNotifiedCriticalMinutes(identity).first())
            assertEquals(false, flagsRepo.getNotifiedCriticalTraffic(identity).first())
            assertEquals(false, flagsRepo.getNotifiedExhaustedMinutes(identity).first())
            assertEquals(false, flagsRepo.getNotifiedExhaustedTraffic(identity).first())
        }
}
