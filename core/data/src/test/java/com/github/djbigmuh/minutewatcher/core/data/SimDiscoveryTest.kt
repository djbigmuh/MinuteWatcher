package com.github.djbigmuh.minutewatcher.core.data

import android.content.Context
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import com.github.djbigmuh.minutewatcher.core.common.util.PermissionManager
import com.github.djbigmuh.minutewatcher.core.domain.PermissionStateNotifier
import com.github.djbigmuh.minutewatcher.core.model.SimDiscoveryResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SimDiscoveryTest {
    private val context: Context = mock()
    private val subscriptionManager: SubscriptionManager = mock()
    private val permissionManager: PermissionManager = mock()
    private val permissionStateNotifier: PermissionStateNotifier = mock()

    private lateinit var discovery: SimIdentityDiscovery
    private val seed = "test-seed"

    @Before
    fun setup() {
        val deps =
            SimDiscoveryDependencies(
                context = context,
                subscriptionManager = subscriptionManager,
                permissionManager = permissionManager,
                permissionStateNotifier = permissionStateNotifier,
            )
        discovery = SimIdentityDiscovery(deps)
    }

    @Test
    fun `Scenario 1 - No permission returns PermissionMissing`() {
        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(false)

        val result = discovery.discoverSims(seed)

        assertEquals(SimDiscoveryResult.PermissionMissing, result)
    }

    @Test
    fun `Scenario 2 - SubscriptionManager returns empty list when permission present`() {
        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
        whenever(permissionManager.getActiveSubscriptions()).thenReturn(emptyList())

        val result = discovery.discoverSims(seed)

        assertTrue(result is SimDiscoveryResult.Available)
        assertTrue((result as SimDiscoveryResult.Available).sims.isEmpty())
    }

    @Test
    fun `Scenario 4 - List order does not change identity`() {
        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)

        val sub1 = createMockSub(1, 0, "icc1")
        val sub2 = createMockSub(2, 1, "icc2")

        whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(sub1, sub2))
        val result1 = discovery.discoverSims(seed) as SimDiscoveryResult.Available

        whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(sub2, sub1))
        val result2 = discovery.discoverSims(seed) as SimDiscoveryResult.Available

        assertEquals(
            result1.sims
                .find { it.subscriptionId == 1 }
                ?.identity
                ?.stableId,
            result2.sims
                .find { it.subscriptionId == 1 }
                ?.identity
                ?.stableId,
        )
        assertEquals(
            result1.sims
                .find { it.subscriptionId == 2 }
                ?.identity
                ?.stableId,
            result2.sims
                .find { it.subscriptionId == 2 }
                ?.identity
                ?.stableId,
        )
    }

    @Test
    fun `Scenario 5 & 6 - Same carrier or display name does not mix identities`() {
        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)

        val sub1 = createMockSub(1, 0, "icc1", "MTS", "MTS")
        val sub2 = createMockSub(2, 1, "icc2", "MTS", "MTS")

        whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(sub1, sub2))
        val result = discovery.discoverSims(seed) as SimDiscoveryResult.Available

        val id1 =
            result.sims
                .find { it.subscriptionId == 1 }
                ?.identity
                ?.stableId
        val id2 =
            result.sims
                .find { it.subscriptionId == 2 }
                ?.identity
                ?.stableId

        assertNotEquals(id2, id1)
    }

    @Test
    fun `Scenario 9 - SIM replacement in same slot changes identity`() {
        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)

        val sub1 = createMockSub(1, 0, "icc1")
        whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(sub1))
        val result1 = discovery.discoverSims(seed) as SimDiscoveryResult.Available
        val id1 =
            result1.sims
                .first()
                .identity.stableId

        val sub2 = createMockSub(2, 0, "icc2") // New SIM in same slot 0
        whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(sub2))
        val result2 = discovery.discoverSims(seed) as SimDiscoveryResult.Available
        val id2 =
            result2.sims
                .first()
                .identity.stableId

        assertNotEquals(id2, id1)
    }

    @Test
    fun `Scenario 11 - Mock mode isolation`() {
        // This is primarily tested in SimIdentityObserver, but here we check discovery itself
        // is pure and doesn't return mocks unless we explicitly implement it in discovery.
        // Our implementation doesn't use MockSimProvider in SimIdentityDiscovery,
        // which is correct (isolation).

        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
        whenever(permissionManager.getActiveSubscriptions()).thenReturn(emptyList())

        val result = discovery.discoverSims(seed) as SimDiscoveryResult.Available
        assertTrue(result.sims.isEmpty())
    }

    @Test
    fun `Scenario 8 - SIM removed returns fewer SIMs`() {
        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)

        val sub1 = createMockSub(1, 0, "icc1")
        val sub2 = createMockSub(2, 1, "icc2")
        whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(sub1, sub2))

        val result1 = discovery.discoverSims(seed) as SimDiscoveryResult.Available
        assertEquals(2, result1.sims.size)

        whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(sub1))
        val result2 = discovery.discoverSims(seed) as SimDiscoveryResult.Available
        assertEquals(1, result2.sims.size)
        assertEquals(
            result1.sims
                .find { it.subscriptionId == 1 }
                ?.identity
                ?.stableId,
            result2.sims
                .first()
                .identity.stableId,
        )
    }

    @Test
    fun `Scenario 7 - Incorrect simSlotIndex is filtered out`() {
        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)

        val sub1 = createMockSub(1, -1, "icc1")
        whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(sub1))

        val result = discovery.discoverSims(seed) as SimDiscoveryResult.Available
        assertTrue(result.sims.isEmpty())
    }

    private fun createMockSub(
        subId: Int,
        slotIndex: Int,
        iccId: String?,
        displayName: String = "SIM $subId",
        carrierName: String = "Carrier $subId",
    ): SubscriptionInfo {
        val sub: SubscriptionInfo = mock()
        whenever(sub.subscriptionId).thenReturn(subId)
        whenever(sub.simSlotIndex).thenReturn(slotIndex)
        whenever(sub.iccId).thenReturn(iccId)
        whenever(sub.displayName).thenReturn(displayName)
        whenever(sub.carrierName).thenReturn(carrierName)
        return sub
    }
}
