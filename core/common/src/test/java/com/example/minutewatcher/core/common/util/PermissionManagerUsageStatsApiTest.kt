package com.example.minutewatcher.core.common.util

import android.app.AppOpsManager
import android.content.Context
import androidx.core.app.AppOpsManagerCompat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

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
        whenever(context.getSystemService(AppOpsManager::class.java)).thenReturn(appOps)
        permissionManager = PermissionManager(context, null)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `hasUsageStatsPermission returns true when allowed`() {
        whenever(appOps.checkOpNoThrow(eq(AppOpsManager.OPSTR_GET_USAGE_STATS), anyInt(), anyString()))
            .thenReturn(AppOpsManagerCompat.MODE_ALLOWED)
        whenever(appOps.unsafeCheckOpNoThrow(eq(AppOpsManager.OPSTR_GET_USAGE_STATS), anyInt(), anyString()))
            .thenReturn(AppOpsManagerCompat.MODE_ALLOWED)

        assertTrue(permissionManager.hasUsageStatsPermission())
    }

    @Test
    @Suppress("DEPRECATION")
    fun `hasUsageStatsPermission returns false when ignored`() {
        whenever(appOps.checkOpNoThrow(eq(AppOpsManager.OPSTR_GET_USAGE_STATS), anyInt(), anyString()))
            .thenReturn(AppOpsManagerCompat.MODE_IGNORED)
        whenever(appOps.unsafeCheckOpNoThrow(eq(AppOpsManager.OPSTR_GET_USAGE_STATS), anyInt(), anyString()))
            .thenReturn(AppOpsManagerCompat.MODE_IGNORED)

        assertFalse(permissionManager.hasUsageStatsPermission())
    }

    @Test
    @Suppress("DEPRECATION")
    fun `hasUsageStatsPermission returns false when exception occurs`() {
        whenever(appOps.checkOpNoThrow(eq(AppOpsManager.OPSTR_GET_USAGE_STATS), anyInt(), anyString()))
            .thenThrow(RuntimeException("AppOps error"))
        whenever(appOps.unsafeCheckOpNoThrow(eq(AppOpsManager.OPSTR_GET_USAGE_STATS), anyInt(), anyString()))
            .thenThrow(RuntimeException("AppOps error"))

        assertFalse(permissionManager.hasUsageStatsPermission())
    }

    @Test
    @Suppress("DEPRECATION")
    fun `hasUsageStatsPermission returns false by default when mode is not allowed`() {
        whenever(appOps.checkOpNoThrow(eq(AppOpsManager.OPSTR_GET_USAGE_STATS), anyInt(), anyString()))
            .thenReturn(AppOpsManagerCompat.MODE_ERRORED)
        whenever(appOps.unsafeCheckOpNoThrow(eq(AppOpsManager.OPSTR_GET_USAGE_STATS), anyInt(), anyString()))
            .thenReturn(AppOpsManagerCompat.MODE_ERRORED)

        assertFalse(permissionManager.hasUsageStatsPermission())
    }
}
