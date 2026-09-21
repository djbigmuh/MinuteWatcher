package com.example.minutewatcher.core.common.util

import android.telephony.SubscriptionInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class TelephonyExtensionsTest {
    @Test
    @Config(sdk = [28]) // behavior-specific SDK
    fun `mccStringCompat on API 28 returns empty for sentinel`() {
        val info: SubscriptionInfo = mock()
        @Suppress("DEPRECATION")
        whenever(info.mcc).thenReturn(Integer.MAX_VALUE)

        assertEquals("", info.mccStringCompat)
    }

    @Test
    @Config(sdk = [28]) // behavior-specific SDK
    fun `mncStringCompat on API 28 returns empty for sentinel`() {
        val info: SubscriptionInfo = mock()
        @Suppress("DEPRECATION")
        whenever(info.mnc).thenReturn(Integer.MAX_VALUE)

        assertEquals("", info.mncStringCompat)
    }

    @Test
    @Config(sdk = [28]) // behavior-specific SDK
    fun `mccStringCompat on API 28 pads valid value`() {
        val info: SubscriptionInfo = mock()
        @Suppress("DEPRECATION")
        whenever(info.mcc).thenReturn(1)

        assertEquals("001", info.mccStringCompat)
    }

    @Test
    @Config(sdk = [34]) // behavior-specific SDK (API 29+)
    fun `mccStringCompat on API 29 plus uses mccString`() {
        val info: SubscriptionInfo = mock()
        whenever(info.mccString).thenReturn("250")

        assertEquals("250", info.mccStringCompat)
    }
}
