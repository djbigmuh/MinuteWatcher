package com.example.minutewatcher.core.data

import android.telephony.SubscriptionInfo
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.IdentitySource
import com.example.minutewatcher.core.model.SimIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        val mockSubInfo = mock<SubscriptionInfo>()
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
    fun `resolve returns StaleInput when system says no default sub but a SIM claims default status`() {
        whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(-1)

        val activeSims = listOf(createActiveSim(1, 2, isDefaultData = true))

        val result = resolver.resolve(activeSims)

        assertEquals(TrafficTargetResolver.TargetResult.StaleInput, result)
    }

    @Test
    fun `resolve returns StaleInput when default data subscription ID is missing in active SIMs`() {
        whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(1)

        val activeSims = listOf(createActiveSim(0, 2, isDefaultData = false))

        val result = resolver.resolve(activeSims)

        assertEquals(TrafficTargetResolver.TargetResult.StaleInput, result)
    }

    @Test
    fun `resolve returns Failed when simSlotIndex is out of supported range`() {
        whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(1)

        val activeSims = listOf(createActiveSim(99, 1, isDefaultData = true))

        val result = resolver.resolve(activeSims)

        assertTrue(result is TrafficTargetResolver.TargetResult.Failed)
        val failed = result as TrafficTargetResolver.TargetResult.Failed
        assertNotNull(failed.message)
        assertTrue(failed.message!!.contains("99"))
    }

    @Test
    fun `resolve returns NoActiveSims when input list is empty`() {
        val result = resolver.resolve(emptyList())
        assertEquals(TrafficTargetResolver.TargetResult.NoActiveSims, result)
    }

    @Test
    fun `resolve returns NoDefaultData when internet is off`() {
        whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(-1)

        val activeSims =
            listOf(
                createActiveSim(0, 1, isDefaultData = false),
                createActiveSim(1, 2, isDefaultData = false),
            )

        val result = resolver.resolve(activeSims)

        assertEquals(TrafficTargetResolver.TargetResult.NoDefaultData, result)
    }

    @Test
    fun `resolve returns Failed when unexpected exception occurs`() {
        whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(1)
        val throwingList =
            object : ArrayList<ActiveSimInfo>() {
                override fun iterator(): MutableIterator<ActiveSimInfo> =
                    throw IllegalStateException("Unexpected error")
            }
        throwingList.add(createActiveSim(0, 1, isDefaultData = true))

        val result = resolver.resolve(throwingList)

        assertTrue(result is TrafficTargetResolver.TargetResult.Failed)
        assertEquals("Unexpected error", (result as TrafficTargetResolver.TargetResult.Failed).message)
    }

    @Test
    fun `resolve handles exception in getDefaultDataSubscriptionId gracefully`() {
        whenever(permissionManager.getDefaultDataSubscriptionId()).thenThrow(RuntimeException("Cancelled"))

        val activeSims = listOf(createActiveSim(0, 1, isDefaultData = true))

        val result = resolver.resolve(activeSims)

        assertEquals(TrafficTargetResolver.TargetResult.StaleInput, result)
    }

    private fun createActiveSim(
        slot: Int,
        subId: Int,
        isDefaultData: Boolean,
    ) = ActiveSimInfo(
        identity = SimIdentity("sim_$slot", subId, slot, IdentitySource.ICCID, isStable = true),
        slotIndex = slot,
        subscriptionId = subId,
        iccId = "sim_$slot",
        displayName = "SIM $slot",
        carrierName = "Carrier",
        mccMnc = "12345",
        isDefaultData = isDefaultData,
        isDefaultVoice = false,
    )
}
