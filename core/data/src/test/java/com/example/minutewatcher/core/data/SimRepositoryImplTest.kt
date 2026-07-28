package com.example.minutewatcher.core.data

import android.content.Context
import android.telephony.SubscriptionManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class SimRepositoryImplTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var subscriptionManager: SubscriptionManager
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var seedRepository: SimIdentitySeedRepository
    private lateinit var permissionStateNotifier: PermissionStateNotifier
    private lateinit var permissionManager: com.example.minutewatcher.core.model.util.PermissionManager

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setup() {
        context = mock()
        subscriptionManager = mock()
        seedRepository = mock()
        permissionStateNotifier = mock()
        permissionManager = mock()

        whenever(seedRepository.getSeed()).thenReturn(flowOf("test_seed"))
        whenever(permissionStateNotifier.changes).thenReturn(flowOf(Unit))

        dataStore =
            PreferenceDataStoreFactory.create(
                scope = testScope,
                produceFile = { tmpFolder.newFile("test_sim_settings.preferences_pb") },
            )
    }

    @Test
    fun testInitialization() {
        assertNotNull(dataStore)
    }

    @Test
    fun testUpdateSimLimitsAndGetUsage() =
        testScope.runTest {
            val repository =
                SimRepositoryImpl(context, subscriptionManager, permissionManager, permissionStateNotifier, dataStore, seedRepository)
            val identity = "test_sim"

            repository.updateSimLimits(identity, 100, 1024)

            val usage = repository.getSimUsage(identity).first()
            assertEquals(100, usage.minutesLimit)
            assertEquals(1024, usage.trafficLimitMb)
        }

    @Test
    fun testActiveSimInSlot1_ReturnsCorrectSlotIndexInUsage() =
        testScope.runTest {
            val repository =
                SimRepositoryImpl(context, subscriptionManager, permissionManager, permissionStateNotifier, dataStore, seedRepository)

            // Mock a SIM in Slot 1 (index 1)
            val mockInfo = mock<android.telephony.SubscriptionInfo>()
            whenever(mockInfo.simSlotIndex).thenReturn(1)
            whenever(mockInfo.subscriptionId).thenReturn(2)
            whenever(mockInfo.iccId).thenReturn("test_iccid_slot1")

            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(mockInfo))

            // Trigger discovery by collecting flow
            val sims = repository.observeActiveSims().first()
            val identity = sims.first().identity.stableId

            // Verify usage has slot 1
            val usage = repository.getSimUsage(identity).first()
            assertEquals(1, usage.slotIndex)
            assertEquals(2, usage.subscriptionId)
        }

    @Test
    fun testActiveSimInSlot2_IsFilteredOut() =
        testScope.runTest {
            val repository =
                SimRepositoryImpl(context, subscriptionManager, permissionManager, permissionStateNotifier, dataStore, seedRepository)

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
}
