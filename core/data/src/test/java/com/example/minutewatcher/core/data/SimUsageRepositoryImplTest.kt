package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.minutewatcher.core.common.testing.InMemoryPreferencesDataStore
import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.common.util.FixedTimeProvider
import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.data.di.DataStoreKeys
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SimUsageRepositoryImplTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val testScope = TestScope(mainDispatcherRule.testDispatcher)
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: SimUsageRepositoryImpl
    private val timeProvider = FixedTimeProvider(currentTime = 1000000L)

    @Before
    fun setup() {
        initTestLogger()
        dataStore = InMemoryPreferencesDataStore()

        repository =
            SimUsageRepositoryImpl(
                dataStore = dataStore,
                timeProvider = timeProvider,
                mapper = SimUsageMapper(),
                resetLogic = SimUsageResetLogic(),
            )
    }

    @Test
    fun `incrementMinutesUsedForCall is idempotent based on signature`() =
        runTest {
            val identity = "test_sim"
            val signature = "1000|5000"

            // 1. First call - should increment
            val firstResult = repository.incrementMinutesUsedForCall(identity, 10, signature)
            assertTrue("First call should succeed", firstResult)

            val usageAfterFirst = repository.getSimUsage(identity).first()
            assertEquals(10, usageAfterFirst.minutesUsed)

            // 2. Second call with SAME signature - should NOT increment
            val secondResult = repository.incrementMinutesUsedForCall(identity, 10, signature)
            assertFalse("Second call with same signature should be ignored", secondResult)

            val usageAfterSecond = repository.getSimUsage(identity).first()
            assertEquals(10, usageAfterSecond.minutesUsed) // Still 10

            // 3. Third call with DIFFERENT signature - should increment
            val thirdResult = repository.incrementMinutesUsedForCall(identity, 5, "1001|5000")
            assertTrue("Third call with different signature should succeed", thirdResult)

            val usageAfterThird = repository.getSimUsage(identity).first()
            assertEquals(15, usageAfterThird.minutesUsed) // 10 + 5 = 15
        }

    @Test
    fun `incrementMinutesUsedForCall returns false for invalid identity`() =
        runTest {
            val invalidIdentity = "invalid_slot_0"
            val result = repository.incrementMinutesUsedForCall(invalidIdentity, 10, "1000|5000")
            assertFalse(result)

            val usage = repository.getSimUsage(invalidIdentity).first()
            assertEquals(0, usage.minutesUsed)
        }

    @Test
    fun `legacy numeric call marker is removed after successful call recording`() =
        runTest {
            val identity = "test_sim"
            dataStore.edit { prefs ->
                prefs[DataStoreKeys.lastCountedCallMarkerKey(identity)] = 9999L
            }

            val result = repository.incrementMinutesUsedForCall(identity, 10, "1000|5000")
            assertTrue(result)

            val prefs = dataStore.data.first()
            assertNull(prefs[DataStoreKeys.lastCountedCallMarkerKey(identity)])
            assertEquals("1000|5000", prefs[DataStoreKeys.lastCountedCallSignatureKey(identity)])
        }

    @Test
    fun `pre existing legacy numeric marker does not affect call recording with new string signature`() =
        runTest {
            val identity = "test_sim"
            dataStore.edit { prefs ->
                prefs[DataStoreKeys.lastCountedCallMarkerKey(identity)] = 5000L
            }

            val result = repository.incrementMinutesUsedForCall(identity, 10, "1000|5000")
            assertTrue("Call with new string signature should succeed despite legacy marker", result)

            val usage = repository.getSimUsage(identity).first()
            assertEquals(10, usage.minutesUsed)
        }

    @Test
    fun `resetSimUsageIfDue initializes window when date is missing`() =
        runTest {
            val identity = "test_sim"
            val now = 1000000L
            repository.setMinutesUsed(identity, 50)

            // First call - should NOT reset but initialize date
            val result = repository.resetSimUsageIfDue(identity, autoResetDay = 1, nowMillis = now)

            assertFalse("Should not reset on first initialization", result)
            val usage = repository.getSimUsage(identity).first()
            assertEquals("Minutes should be preserved", 50, usage.minutesUsed)

            val lastReset = repository.getLastResetDate(identity).first()
            assertEquals("Last reset date should be set to now", now, lastReset)
        }

    @Test
    fun `resetSimUsageIfDue skips reset in same period after initialization`() =
        runTest {
            val identity = "test_sim"
            val now = 1000000L // Assume this is day 10
            repository.setMinutesUsed(identity, 50)

            // 1. Initialize
            repository.resetSimUsageIfDue(identity, autoResetDay = 1, nowMillis = now)

            // 2. Second call in same period (e.g., day 15)
            val laterSamePeriod = now + 5 * 24 * 3600 * 1000L
            repository.setMinutesUsed(identity, 60)
            val result = repository.resetSimUsageIfDue(identity, autoResetDay = 1, nowMillis = laterSamePeriod)

            assertFalse("Should not reset in same period", result)
            val usage = repository.getSimUsage(identity).first()
            assertEquals(60, usage.minutesUsed)
        }

    @Test
    fun `resetSimUsageIfDue performs reset in next period`() =
        runTest {
            val identity = "test_sim"
            val now = 1000000L // Day 1
            repository.setMinutesUsed(identity, 50)

            // 1. Initialize
            repository.resetSimUsageIfDue(identity, autoResetDay = 1, nowMillis = now)

            // 2. Next month
            val nextMonth = now + 32 * 24 * 3600 * 1000L
            val result = repository.resetSimUsageIfDue(identity, autoResetDay = 1, nowMillis = nextMonth)

            assertTrue("Should perform reset in next period", result)
            val usage = repository.getSimUsage(identity).first()
            assertEquals(0, usage.minutesUsed)

            val lastReset = repository.getLastResetDate(identity).first()
            assertEquals(nextMonth, lastReset)
        }

    @Test
    fun `resetSimUsageIfDue respects rollover in next period`() =
        runTest {
            val identity = "test_sim"
            val now = 1000000L
            repository.setMinutesUsed(identity, 50)

            // Set limit and enable rollover
            dataStore.edit { prefs ->
                prefs[DataStoreKeys.monthlyLimitKey(identity)] = 100
                prefs[DataStoreKeys.rolloverEnabledKey(identity)] = true
            }

            // 1. Initialize
            repository.resetSimUsageIfDue(identity, autoResetDay = 1, nowMillis = now)

            // 2. Next month
            val nextMonth = now + 32 * 24 * 3600 * 1000L
            val result = repository.resetSimUsageIfDue(identity, autoResetDay = 1, nowMillis = nextMonth)

            assertTrue(result)
            val usage = repository.getSimUsage(identity).first()
            assertEquals(0, usage.minutesUsed)
            assertEquals("Rollover minutes should be 50 (100 - 50)", 50, usage.manualAdjustment)
        }

    @Test
    fun `resetSimUsageIfDue discards manual adjustment in next period when rollover is disabled`() =
        runTest {
            val identity = "test_sim"
            val now = 1000000L
            repository.setMinutesUsed(identity, 50)

            dataStore.edit { prefs ->
                prefs[DataStoreKeys.monthlyLimitKey(identity)] = 100
                prefs[DataStoreKeys.manualAdjustmentKey(identity)] = 30
                prefs[DataStoreKeys.rolloverEnabledKey(identity)] = false
            }

            // 1. Initialize window
            repository.resetSimUsageIfDue(identity, autoResetDay = 1, nowMillis = now)

            // 2. Next month
            val nextMonth = now + 32 * 24 * 3600 * 1000L
            val result = repository.resetSimUsageIfDue(identity, autoResetDay = 1, nowMillis = nextMonth)

            assertTrue(result)
            val usage = repository.getSimUsage(identity).first()
            assertEquals(0, usage.minutesUsed)
            assertEquals("Manual adjustment should be discarded without rollover", 0, usage.manualAdjustment)
        }

    @Test
    fun `resetSimUsageIfDue includes manual adjustment in carried over balance when rollover is enabled`() =
        runTest {
            val identity = "test_sim"
            val now = 1000000L
            repository.setMinutesUsed(identity, 50)

            dataStore.edit { prefs ->
                prefs[DataStoreKeys.monthlyLimitKey(identity)] = 100
                prefs[DataStoreKeys.manualAdjustmentKey(identity)] = 30
                prefs[DataStoreKeys.rolloverEnabledKey(identity)] = true
            }

            // 1. Initialize window
            repository.resetSimUsageIfDue(identity, autoResetDay = 1, nowMillis = now)

            // 2. Next month
            val nextMonth = now + 32 * 24 * 3600 * 1000L
            val result = repository.resetSimUsageIfDue(identity, autoResetDay = 1, nowMillis = nextMonth)

            assertTrue(result)
            val usage = repository.getSimUsage(identity).first()
            assertEquals(0, usage.minutesUsed)
            assertEquals("Rollover should include adjustment (100 + 30 - 50 = 80)", 80, usage.manualAdjustment)
        }
}
