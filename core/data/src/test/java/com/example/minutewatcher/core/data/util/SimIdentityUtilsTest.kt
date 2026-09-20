package com.example.minutewatcher.core.data.util

import android.telephony.SubscriptionInfo
import com.example.minutewatcher.core.common.util.mccStringCompat
import com.example.minutewatcher.core.common.util.mncStringCompat
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class SimIdentityUtilsTest {
    @Test
    @Config(sdk = [28]) // behavior-specific SDK
    fun `mccStringCompat and mncStringCompat on API 28 normalize MCC and return raw MNC`() {
        val info: SubscriptionInfo = mock()
        @Suppress("DEPRECATION")
        whenever(info.mcc).thenReturn(250)
        @Suppress("DEPRECATION")
        whenever(info.mnc).thenReturn(1)

        assertEquals("250", info.mccStringCompat)
        assertEquals("1", info.mncStringCompat)
    }

    @Test
    @Config(sdk = [28]) // behavior-specific SDK
    fun `mccMncCandidates on API 28 returns raw and padded variations`() {
        val info: SubscriptionInfo = mock()
        @Suppress("DEPRECATION")
        whenever(info.mcc).thenReturn(250)
        @Suppress("DEPRECATION")
        whenever(info.mnc).thenReturn(1)

        val candidates = SimIdentityUtils.mccMncCandidates(info)
        // mcc=250, mncRaw=1 -> 2501, 25001, 250001
        assertEquals(listOf("2501", "25001", "250001"), candidates)
    }

    @Test
    @Config(sdk = [28]) // behavior-specific SDK
    fun `mccMncCandidates on API 28 returns empty when mcc is unavailable`() {
        val info: SubscriptionInfo = mock()
        @Suppress("DEPRECATION")
        whenever(info.mcc).thenReturn(Integer.MAX_VALUE)
        @Suppress("DEPRECATION")
        whenever(info.mnc).thenReturn(1)

        val candidates = SimIdentityUtils.mccMncCandidates(info)
        assertEquals(emptyList<String>(), candidates)
    }

    @Test
    @Config(sdk = [28]) // behavior-specific SDK
    fun `mccMncCandidates on API 28 returns empty when mnc is unavailable`() {
        val info: SubscriptionInfo = mock()
        @Suppress("DEPRECATION")
        whenever(info.mcc).thenReturn(250)
        @Suppress("DEPRECATION")
        whenever(info.mnc).thenReturn(Integer.MAX_VALUE)

        val candidates = SimIdentityUtils.mccMncCandidates(info)
        assertEquals(emptyList<String>(), candidates)
    }

    @Test
    @Config(sdk = [34]) // behavior-specific SDK (API 29+)
    fun `mccMncCandidates on API 29 plus returns single canonical candidate`() {
        val info: SubscriptionInfo = mock()
        whenever(info.mccString).thenReturn("250")
        whenever(info.mncString).thenReturn("01")

        val candidates = SimIdentityUtils.mccMncCandidates(info)
        assertEquals(listOf("25001"), candidates)
    }
}
