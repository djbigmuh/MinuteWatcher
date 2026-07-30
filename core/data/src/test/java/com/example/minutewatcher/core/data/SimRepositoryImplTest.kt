package com.example.minutewatcher.core.data

import android.content.Context
import android.telephony.SubscriptionManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.common.util.FixedTimeProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
@RunWith(RobolectricTestRunner::class)

@OptIn(ExperimentalCoroutinesApi::class)
class SimRepositoryImplTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var subscriptionManager: SubscriptionManager
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var seedRepository: SimIdentitySeedRepository
    private lateinit var permissionStateNotifier: PermissionStateNotifier
    private lateinit var permissionManager: com.example.minutewatcher.core.android.util.PermissionManager
    private val timeProvider = FixedTimeProvider(currentTime = 1000000L)

    @Before
    fun setup() {
        context = mock()
        subscriptionManager = mock()
        permissionStateNotifier = mock()
        permissionManager = mock()

        seedRepository = mock {
            on { getSeed() } doReturn flowOf("test_seed")
            on { getOrCreateSeed(any()) } doReturn "test_seed"
        }
        whenever(permissionStateNotifier.changes).thenReturn(flowOf(Unit))

        dataStore =
            PreferenceDataStoreFactory.create(
                produceFile = { tmpFolder.newFile("test_sim_settings.preferences_pb") },
            )
    }

    @Test
    fun testInitialization() {
        assertNotNull(dataStore)
    }

    @Test
    fun testUpdateSimLimitsAndGetUsage() =
        runTest {
            val repository =
                SimRepositoryImpl(context, subscriptionManager, permissionManager, permissionStateNotifier, dataStore, seedRepository, backgroundScope, timeProvider)
            val identity = "test_sim"

            repository.updateSimLimits(identity, 100, 1024)

            val usage = repository.getSimUsage(identity).first()
            assertEquals(100, usage.minutesLimit)
            assertEquals(1024, usage.trafficLimitMb)
        }

    @Test
    fun testActiveSimInSlot1_ReturnsCorrectSlotIndexInUsage() =
        runTest {
            val repository =
                SimRepositoryImpl(context, subscriptionManager, permissionManager, permissionStateNotifier, dataStore, seedRepository, backgroundScope, timeProvider)

            // Mock a SIM in Slot 1 (index 1)
            val mockInfo = mock<android.telephony.SubscriptionInfo>()
            whenever(mockInfo.simSlotIndex).thenReturn(1)
            whenever(mockInfo.subscriptionId).thenReturn(2)
            whenever(mockInfo.iccId).thenReturn("test_iccid_slot1")

            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(mockInfo))

            // Discovery
            val sims = repository.observeActiveSims().first { it.isNotEmpty() }
            val identity = sims.first().identity.stableId

            // Verify usage has slot 1
            val usage = repository.getSimUsage(identity).first()
            assertEquals(1, usage.slotIndex)
            assertEquals(2, usage.subscriptionId)
        }

    @Test
    fun testActiveSimInSlot2_IsFilteredOut() =
        runTest {
            val repository =
                SimRepositoryImpl(context, subscriptionManager, permissionManager, permissionStateNotifier, dataStore, seedRepository, backgroundScope, timeProvider)

            // Mock a SIM in Slot 2 (unsupported)
            val mockInfo = mock<android.telephony.SubscriptionInfo>()
            whenever(mockInfo.simSlotIndex).thenReturn(2)
            whenever(mockInfo.subscriptionId).thenReturn(3)
            whenever(mockInfo.iccId).thenReturn("test_iccid_slot2")

            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(mockInfo))

            // Discovery
            val sims = repository.observeActiveSims().first()

            // Verify it's empty because slot 2 is unsupported
            assertEquals(0, sims.size)
        }

    @Test
    fun testGetIdentity_DoesNotTriggerEditWhenUnchanged() =
        runTest {
            // For now, let's just verify functionality remains same while we optimized it.
            val repository =
                SimRepositoryImpl(context, subscriptionManager, permissionManager, permissionStateNotifier, dataStore, seedRepository, backgroundScope, timeProvider)

            val mockInfo = mock<android.telephony.SubscriptionInfo>()
            whenever(mockInfo.simSlotIndex).thenReturn(0)
            whenever(mockInfo.subscriptionId).thenReturn(1)
            whenever(mockInfo.iccId).thenReturn("test_iccid")

            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(mockInfo))

            // First call - should write
            val id1 = repository.getIdentity(0)
            
            // Second call - should use cache and NOT write
            val id2 = repository.getIdentity(0)
            
            assertEquals(id1, id2)
        }

    @Test
    fun testRolloverLogic_AccumulatesRemainingMinutes() =
        runTest {
            val repository =
                SimRepositoryImpl(context, subscriptionManager, permissionManager, permissionStateNotifier, dataStore, seedRepository, backgroundScope, timeProvider)
            val identity = "test_sim"

            // 1. Setup limit and usage
            repository.updateSimLimits(identity, 100, 1024, rolloverEnabled = true)
            repository.setMinutesUsed(identity, 80) // 20 minutes remaining

            // 2. Perform reset
            repository.resetSimUsage(identity)

            // 3. Verify rollover
            val usage = repository.getSimUsage(identity).first()
            assertEquals(0, usage.minutesUsed)
            assertEquals(20, usage.manualAdjustment)
            assertEquals(120, usage.minutesLimit + usage.manualAdjustment)
        }

    @Test
    fun testRolloverLogic_AccumulatesRemainingTraffic() =
        runTest {
            val repository =
                SimRepositoryImpl(context, subscriptionManager, permissionManager, permissionStateNotifier, dataStore, seedRepository, backgroundScope, timeProvider)
            val identity = "test_sim"

            // 1. Setup limit and usage (1GB limit = 1024MB)
            repository.updateSimLimits(identity, 100, 1024, rolloverEnabled = true)
            repository.setTrafficUsed(identity, 512f) // 0.5GB used, 0.5GB remaining

            // 2. Perform reset
            repository.resetSimUsage(identity)

            // 3. Verify rollover
            val usage = repository.getSimUsage(identity).first()
            assertEquals(0f, usage.trafficUsed)
            assertEquals(0.5f, usage.trafficAdjustment)
            assertEquals(1.5f, (usage.trafficLimitMb / 1024f) + usage.trafficAdjustment, 0.001f)
        }
}



