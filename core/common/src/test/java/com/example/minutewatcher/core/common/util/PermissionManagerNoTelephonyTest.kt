package com.example.minutewatcher.core.common.util

import android.content.Context
import android.content.pm.PackageManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class PermissionManagerNoTelephonyTest {

    @Test
    fun `isTelephonySupported returns false when service missing`() {
        val context: Context = mock()
        val packageManager: PackageManager = mock()
        whenever(context.packageManager).thenReturn(packageManager)
        whenever(packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)).thenReturn(false)

        val manager = PermissionManager(context, null)

        assertFalse(manager.isTelephonySupported())
    }

    @Test
    fun `getActiveSubscriptions returns empty list when service missing`() {
        val context: Context = mock()
        val manager = PermissionManager(context, null)

        assertTrue(manager.getActiveSubscriptions().isEmpty())
    }
}
