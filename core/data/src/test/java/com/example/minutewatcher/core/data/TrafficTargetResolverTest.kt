package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.IdentitySource
import com.example.minutewatcher.core.model.SimIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class TrafficTargetResolverTest {
    private val permissionManager: PermissionManager = mock()
    private lateinit var resolver: TrafficTargetResolver

    @Before
    fun setup() {
        initTestLogger()
        resolver = TrafficTargetResolver(permissionManager)
    }

    @Test
    fun `resolve returns Success when default data SIM is identified via SubscriptionManager`() {
        whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(1)
        val mockSubInfo = mock<android.telephony.SubscriptionInfo>()
        whenever(mockSubInfo.subscriptionId).thenReturn(1)
        whenever(mockSubInfo.simSlotIndex).thenReturn(0)
        whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(mockSubInfo))

        val activeSims = listOf(createActiveSim(0, 1, isDefaultData = true))

        val result = resolver.resolve(activeSims)

        assertTrue(result is TrafficTargetResolver.TargetResult.Success)
        assertEquals(0, (result as TrafficTargetResolver.TargetResult.Success).target.slot)
        assertEquals(1, result.target.dataSubId)
    }

    @Test
    fun `resolve returns Success when default data SIM is identified via ActiveSimInfo`() {
        // SubscriptionManager returns -1 (e.g. transient state)
        whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(-1)

        val activeSims = listOf(createActiveSim(1, 2, isDefaultData = true))

        val result = resolver.resolve(activeSims)

        assertTrue(result is TrafficTargetResolver.TargetResult.Success)
        assertEquals(1, (result as TrafficTargetResolver.TargetResult.Success).target.slot)
        assertEquals(2, result.target.dataSubId)
    }

    @Test
    fun `resolve returns NoActiveSims when input list is empty`() {
        val result = resolver.resolve(emptyList())
        assertEquals(TrafficTargetResolver.TargetResult.NoActiveSims, result)
    }

    @Test
    fun `resolve returns InconsistentFirmware instead of fallback when no default data SIM identified`() {
        whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(-1)

        // Active SIMs exist but none is marked as default data
        val activeSims =
            listOf(
                createActiveSim(0, 1, isDefaultData = false),
                createActiveSim(1, 2, isDefaultData = false),
            )

        val result = resolver.resolve(activeSims)

        assertEquals(TrafficTargetResolver.TargetResult.InconsistentFirmware, result)
    }

    private fun createActiveSim(
        slot: Int,
        subId: Int,
        isDefaultData: Boolean,
    ) = ActiveSimInfo(
        identity = SimIdentity("sim_$slot", subId, slot, IdentitySource.ICCID),
        slotIndex = slot,
        subscriptionId = subId,
        displayName = "SIM $slot",
        carrierName = "Carrier",
        mccMnc = "12345",
        isDefaultData = isDefaultData,
        isDefaultVoice = false,
    )
}
