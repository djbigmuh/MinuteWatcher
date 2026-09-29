package com.example.minutewatcher.core.common.util

import android.app.AppOpsManager
import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class PermissionManagerUsageStatsApiTest {
    private val context: Context = mock()
    private val appOps: AppOpsManager = mock()
    private lateinit var permissionManager: PermissionManager

    @Before
    fun setup() {
        initTestLogger(context)
        whenever(context.packageName).thenReturn("com.example.minutewatcher")
        whenever(context.getSystemService(Context.APP_OPS_SERVICE)).thenReturn(appOps)
        permissionManager = PermissionManager(context, null)
    }

    @Test
    @Config(sdk = [33]) // behavior-specific SDK
    // @Suppress("DEPRECATION") is required because test checks deprecated AppOpsManager methods on API 29+.
    @Suppress("DEPRECATION")
    fun `hasUsageStatsPermission on API 33 uses unsafeCheckOpNoThrow and returns true when allowed`() {
        whenever(appOps.unsafeCheckOpNoThrow(eq(AppOpsManager.OPSTR_GET_USAGE_STATS), anyInt(), anyString()))
            .thenReturn(AppOpsManager.MODE_ALLOWED)

        assertTrue(permissionManager.hasUsageStatsPermission())
        verify(appOps, never()).checkOpNoThrow(eq(AppOpsManager.OPSTR_GET_USAGE_STATS), anyInt(), anyString())
    }

    @Test
    @Config(sdk = [33]) // behavior-specific SDK
    // @Suppress("DEPRECATION") is required because test checks deprecated AppOpsManager methods on API 29+.
    @Suppress("DEPRECATION")
    fun `hasUsageStatsPermission on API 33 returns false when ignored`() {
        whenever(appOps.unsafeCheckOpNoThrow(eq(AppOpsManager.OPSTR_GET_USAGE_STATS), anyInt(), anyString()))
            .thenReturn(AppOpsManager.MODE_IGNORED)

        assertFalse(permissionManager.hasUsageStatsPermission())
    }

    @Test
    @Config(sdk = [33]) // behavior-specific SDK
    // @Suppress("DEPRECATION") is required because test checks deprecated AppOpsManager methods on API 29+.
    @Suppress("DEPRECATION")
    fun `hasUsageStatsPermission on API 33 returns false when exception occurs`() {
        whenever(appOps.unsafeCheckOpNoThrow(eq(AppOpsManager.OPSTR_GET_USAGE_STATS), anyInt(), anyString()))
            .thenThrow(RuntimeException("API 33 error"))

        assertFalse(permissionManager.hasUsageStatsPermission())
    }

    @Test
    @Config(sdk = [28]) // behavior-specific SDK
    // @Suppress("DEPRECATION") is required because checkOpNoThrow is deprecated on API 29+.
    @Suppress("DEPRECATION")
    fun `hasUsageStatsPermission on API 28 uses checkOpNoThrow and returns true when allowed`() {
        whenever(appOps.checkOpNoThrow(eq(AppOpsManager.OPSTR_GET_USAGE_STATS), anyInt(), anyString()))
            .thenReturn(AppOpsManager.MODE_ALLOWED)

        assertTrue(permissionManager.hasUsageStatsPermission())
        verify(appOps).checkOpNoThrow(eq(AppOpsManager.OPSTR_GET_USAGE_STATS), anyInt(), anyString())
    }
}
