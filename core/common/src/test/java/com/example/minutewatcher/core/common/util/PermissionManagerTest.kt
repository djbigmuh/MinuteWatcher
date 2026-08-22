package com.example.minutewatcher.core.common.util

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class PermissionManagerTest {
    private val context: Context = mock()
    private val subscriptionManager: SubscriptionManager = mock()
    private lateinit var permissionManager: PermissionManager

    @Before
    fun setup() {
        initTestLogger(context)
        whenever(context.packageName).thenReturn("com.example.minutewatcher")
        whenever(context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)).thenReturn(subscriptionManager)
        permissionManager = PermissionManager(context)
    }

    @Test
    fun `hasPhoneStatePermission returns true when granted`() {
        mockStatic(ContextCompat::class.java).use { mockedContextCompat ->
            mockedContextCompat
                .`when`<Int> {
                    ContextCompat.checkSelfPermission(any(), eq(Manifest.permission.READ_PHONE_STATE))
                }.thenReturn(PackageManager.PERMISSION_GRANTED)

            assertTrue(permissionManager.hasPhoneStatePermission())
        }
    }

    @Test
    fun `hasPhoneStatePermission returns false when denied`() {
        mockStatic(ContextCompat::class.java).use { mockedContextCompat ->
            mockedContextCompat
                .`when`<Int> {
                    ContextCompat.checkSelfPermission(any(), eq(Manifest.permission.READ_PHONE_STATE))
                }.thenReturn(PackageManager.PERMISSION_DENIED)

            assertFalse(permissionManager.hasPhoneStatePermission())
        }
    }

    @Test
    fun `getActiveSubscriptions returns empty list when permission is missing`() {
        mockStatic(ContextCompat::class.java).use { mockedContextCompat ->
            mockedContextCompat
                .`when`<Int> {
                    ContextCompat.checkSelfPermission(any(), eq(Manifest.permission.READ_PHONE_STATE))
                }.thenReturn(PackageManager.PERMISSION_DENIED)

            assertTrue(permissionManager.getActiveSubscriptions().isEmpty())
        }
    }

    @Test
    fun `getDefaultVoiceSubscriptionId returns negative one when permission is missing`() {
        mockStatic(ContextCompat::class.java).use { mockedContextCompat ->
            mockedContextCompat
                .`when`<Int> {
                    ContextCompat.checkSelfPermission(any(), eq(Manifest.permission.READ_PHONE_STATE))
                }.thenReturn(PackageManager.PERMISSION_DENIED)

            assertEquals(-1, permissionManager.getDefaultVoiceSubscriptionId())
        }
    }

    @Test
    fun `getDefaultDataSubscriptionId returns negative one when permission is missing`() {
        mockStatic(ContextCompat::class.java).use { mockedContextCompat ->
            mockedContextCompat
                .`when`<Int> {
                    ContextCompat.checkSelfPermission(any(), eq(Manifest.permission.READ_PHONE_STATE))
                }.thenReturn(PackageManager.PERMISSION_DENIED)

            assertEquals(-1, permissionManager.getDefaultDataSubscriptionId())
        }
    }

    @Test
    fun `hasUsageStatsPermission returns true when allowed`() {
        val appOps: AppOpsManager = mock()
        whenever(context.getSystemService(Context.APP_OPS_SERVICE)).thenReturn(appOps)
        whenever(appOps.checkOpNoThrow(eq(AppOpsManager.OPSTR_GET_USAGE_STATS), any(), any()))
            .thenReturn(AppOpsManager.MODE_ALLOWED)

        assertTrue(permissionManager.hasUsageStatsPermission())
    }

    @Test
    fun `hasUsageStatsPermission returns false when ignored`() {
        val appOps: AppOpsManager = mock()
        whenever(context.getSystemService(Context.APP_OPS_SERVICE)).thenReturn(appOps)
        whenever(appOps.checkOpNoThrow(eq(AppOpsManager.OPSTR_GET_USAGE_STATS), any(), any()))
            .thenReturn(AppOpsManager.MODE_IGNORED)

        assertFalse(permissionManager.hasUsageStatsPermission())
    }

    @Test
    fun `hasUsageStatsPermission returns false when exception occurs`() {
        val appOps: AppOpsManager = mock()
        whenever(context.getSystemService(Context.APP_OPS_SERVICE)).thenReturn(appOps)
        whenever(appOps.checkOpNoThrow(eq(AppOpsManager.OPSTR_GET_USAGE_STATS), any(), any()))
            .thenThrow(RuntimeException("Test error"))

        assertFalse(permissionManager.hasUsageStatsPermission())
    }
}
