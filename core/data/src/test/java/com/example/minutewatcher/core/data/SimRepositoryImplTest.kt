package com.example.minutewatcher.core.data

import android.content.Context
import android.telephony.SubscriptionManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.example.minutewatcher.core.common.util.FixedTimeProvider
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.any
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
    private lateinit var appSettingsRepository: com.example.minutewatcher.core.domain.AppSettingsRepository
    private lateinit var permissionManager: com.example.minutewatcher.core.android.util.PermissionManager
    private val timeProvider = FixedTimeProvider(currentTime = 1000000L)

    @Before
    fun setup() {
        context = mock()
        subscriptionManager = mock()
        permissionStateNotifier = mock()
        permissionManager = mock()
        appSettingsRepository =
            mock {
                on { observeAppSettings() } doReturn
                    kotlinx.coroutines.flow.MutableStateFlow(
                        com.example.minutewatcher.core.model
                            .AppSettings(),
                    )
            }

        seedRepository =
            mock {
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
            val limitsRepo = SimLimitsRepositoryImpl(dataStore)
            val usageRepo = SimUsageRepositoryImpl(dataStore, timeProvider)
            val identity = "test_sim"

            limitsRepo.updateSimLimits(identity, 100, 1024)

            val usage = usageRepo.getSimUsage(identity).first()
            assertEquals(100, usage.minutesLimit)
            assertEquals(1024, usage.trafficLimitMb)
        }

    @Test
    fun testActiveSimInSlot1_ReturnsCorrectSlotIndexInUsage() =
        runTest {
            val identityRepo =
                SimIdentityRepositoryImpl(
                    context,
                    subscriptionManager,
                    permissionManager,
                    permissionStateNotifier,
                    dataStore,
                    seedRepository,
                    appSettingsRepository,
                    backgroundScope,
                )
            val usageRepo = SimUsageRepositoryImpl(dataStore, timeProvider)

            // Mock a SIM in Slot 1 (index 1)
            val mockInfo = mock<android.telephony.SubscriptionInfo>()
            whenever(mockInfo.simSlotIndex).thenReturn(1)
            whenever(mockInfo.subscriptionId).thenReturn(2)
            whenever(mockInfo.iccId).thenReturn("test_iccid_slot1")

            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(mockInfo))

            // Discovery
            val sims = identityRepo.observeActiveSims().first { it.isNotEmpty() }
            val identity = sims.first().identity.stableId

            // Verify usage has slot 1
            val usage = usageRepo.getSimUsage(identity).first()
            assertEquals(1, usage.slotIndex)
            assertEquals(2, usage.subscriptionId)
        }

    @Test
    fun testGetIdentity_DoesNotTriggerEditWhenUnchanged() =
        runTest {
            val identityRepo =
                SimIdentityRepositoryImpl(
                    context,
                    subscriptionManager,
                    permissionManager,
                    permissionStateNotifier,
                    dataStore,
                    seedRepository,
                    appSettingsRepository,
                    backgroundScope,
                )

            val mockInfo = mock<android.telephony.SubscriptionInfo>()
            whenever(mockInfo.simSlotIndex).thenReturn(0)
            whenever(mockInfo.subscriptionId).thenReturn(1)
            whenever(mockInfo.iccId).thenReturn("test_iccid")

            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(mockInfo))

            // First call - should write
            val id1 = identityRepo.getIdentity(0)

            // Second call - should use cache and NOT write
            val id2 = identityRepo.getIdentity(0)

            assertEquals(id1, id2)
        }

    @Test
    fun testRolloverLogic_AccumulatesRemainingMinutes() =
        runTest {
            val limitsRepo = SimLimitsRepositoryImpl(dataStore)
            val usageRepo = SimUsageRepositoryImpl(dataStore, timeProvider)
            val identity = "test_sim"

            // 1. Setup limit and usage
            limitsRepo.updateSimLimits(identity, 100, 1024, rolloverEnabled = true)
            usageRepo.setMinutesUsed(identity, 80) // 20 minutes remaining

            // 2. Perform reset
            usageRepo.resetSimUsage(identity)

            // 3. Verify rollover
            val usage = usageRepo.getSimUsage(identity).first()
            assertEquals(0, usage.minutesUsed)
            assertEquals(20, usage.manualAdjustment)
            assertEquals(120, usage.minutesLimit + usage.manualAdjustment)
        }

    @Test
    fun testRolloverLogic_AccumulatesRemainingTraffic() =
        runTest {
            val limitsRepo = SimLimitsRepositoryImpl(dataStore)
            val usageRepo = SimUsageRepositoryImpl(dataStore, timeProvider)
            val identity = "test_sim"

            // 1. Setup limit and usage (1GB limit = 1024MB)
            limitsRepo.updateSimLimits(identity, 100, 1024, rolloverEnabled = true)
            usageRepo.setTrafficUsed(identity, 512f) // 0.5GB used, 0.5GB remaining

            // 2. Perform reset
            usageRepo.resetSimUsage(identity)

            // 3. Verify rollover
            val usage = usageRepo.getSimUsage(identity).first()
            assertEquals(0f, usage.trafficUsed)
            assertEquals(0.5f, usage.trafficAdjustment)
            assertEquals(1.5f, (usage.trafficLimitMb / 1024f) + usage.trafficAdjustment, 0.001f)
        }

    @Test
    fun testManualReset_ClearsEverythingEvenWithRollover() =
        runTest {
            val limitsRepo = SimLimitsRepositoryImpl(dataStore)
            val usageRepo = SimUsageRepositoryImpl(dataStore, timeProvider)
            val identity = "test_sim"

            // 1. Setup limit, usage and rollover
            limitsRepo.updateSimLimits(identity, 100, 1024, rolloverEnabled = true)
            usageRepo.setMinutesUsed(identity, 80)
            usageRepo.setTrafficUsed(identity, 512f)

            // 2. Perform MANUAL reset
            usageRepo.resetSimUsageManual(identity)

            // 3. Verify EVERYTHING is cleared
            val usage = usageRepo.getSimUsage(identity).first()
            assertEquals(0, usage.minutesUsed)
            assertEquals(0f, usage.trafficUsed)
            assertEquals(0, usage.manualAdjustment)
            assertEquals(0f, usage.trafficAdjustment)
        }

    @Test
    fun testGetSimIdentity_DoesNotSaveFallback_WhenPermissionsMissing() =
        runTest {
            val identityRepo =
                SimIdentityRepositoryImpl(
                    context,
                    subscriptionManager,
                    permissionManager,
                    permissionStateNotifier,
                    dataStore,
                    seedRepository,
                    appSettingsRepository,
                    backgroundScope,
                )

            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(false)
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(emptyList())

            val identity = identityRepo.getSimIdentity(0)

            assertEquals("sim_0", identity.stableId)
            assertEquals(com.example.minutewatcher.core.model.IdentitySource.SLOT_FALLBACK, identity.source)

            // Verify nothing was saved to DataStore
            val prefs = dataStore.data.first()
            assertEquals(0, prefs.asMap().size)
        }
}
