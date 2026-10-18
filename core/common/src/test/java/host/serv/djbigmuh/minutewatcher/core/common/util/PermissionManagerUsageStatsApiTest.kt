package host.serv.djbigmuh.minutewatcher.core.common.util

import android.app.AppOpsManager
import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Suppress("DEPRECATION")
class PermissionManagerUsageStatsApiTest {
    private val context: Context = mock()
    private val appOps: AppOpsManager = mock()
    private lateinit var permissionManager: PermissionManager

    @Before
    fun setup() {
        initTestLogger(context)
        whenever(context.packageName).thenReturn("host.serv.djbigmuh.minutewatcher")
        whenever(context.getSystemService(Context.APP_OPS_SERVICE)).thenReturn(appOps)
        whenever(context.getSystemService(AppOpsManager::class.java)).thenReturn(appOps)
        permissionManager = PermissionManager(context, null)
    }

    @Test
    @Config(sdk = [28]) // behavior-specific SDK
    fun `hasUsageStatsPermission uses checkOpNoThrow on API 26-28 and avoids note and unsafe ops`() {
        val realContext: Context = org.robolectric.RuntimeEnvironment.getApplication()
        val realAppOps = realContext.getSystemService(AppOpsManager::class.java)
        shadowOf(realAppOps).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            realContext.packageName,
            AppOpsManager.MODE_ALLOWED,
        )
        val realPermissionManager = PermissionManager(realContext, null)

        assertTrue(realPermissionManager.hasUsageStatsPermission())
    }

    @Test
    @Config(sdk = [28]) // behavior-specific SDK
    fun `hasUsageStatsPermission returns false when ignored on API 26-28`() {
        val realContext: Context = org.robolectric.RuntimeEnvironment.getApplication()
        val realAppOps = realContext.getSystemService(AppOpsManager::class.java)
        shadowOf(realAppOps).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            realContext.packageName,
            AppOpsManager.MODE_IGNORED,
        )
        val realPermissionManager = PermissionManager(realContext, null)

        assertFalse(realPermissionManager.hasUsageStatsPermission())
    }

    @Test
    @Config(sdk = [34]) // behavior-specific SDK
    fun `hasUsageStatsPermission uses unsafeCheckOpNoThrow on API 29+ and avoids note and legacy check ops`() {
        whenever(appOps.unsafeCheckOpNoThrow(eq(AppOpsManager.OPSTR_GET_USAGE_STATS), anyInt(), anyString()))
            .thenReturn(AppOpsManager.MODE_ALLOWED)

        assertTrue(permissionManager.hasUsageStatsPermission())

        verify(appOps).unsafeCheckOpNoThrow(eq(AppOpsManager.OPSTR_GET_USAGE_STATS), anyInt(), anyString())
        verify(appOps, never()).checkOpNoThrow(anyString(), anyInt(), anyString())
        verify(appOps, never()).noteOpNoThrow(any(), anyInt(), anyString())
        verify(appOps, never()).noteOp(any(), anyInt(), anyString())
        verify(appOps, never()).noteProxyOpNoThrow(any(), any())
        verify(appOps, never()).noteProxyOp(any(), any())
    }

    @Test
    @Config(sdk = [34]) // behavior-specific SDK
    fun `hasUsageStatsPermission returns false when ignored on API 29+`() {
        whenever(appOps.unsafeCheckOpNoThrow(eq(AppOpsManager.OPSTR_GET_USAGE_STATS), anyInt(), anyString()))
            .thenReturn(AppOpsManager.MODE_IGNORED)

        assertFalse(permissionManager.hasUsageStatsPermission())
    }

    @Test
    fun `hasUsageStatsPermission returns false when exception occurs`() {
        whenever(appOps.unsafeCheckOpNoThrow(any(), anyInt(), anyString()))
            .thenThrow(RuntimeException("AppOps error"))

        assertFalse(permissionManager.hasUsageStatsPermission())
    }

    @Test
    fun `hasUsageStatsPermission returns false when appOps service is missing`() {
        whenever(context.getSystemService(AppOpsManager::class.java)).thenReturn(null)
        whenever(context.getSystemService(Context.APP_OPS_SERVICE)).thenReturn(null)

        assertFalse(permissionManager.hasUsageStatsPermission())
    }
}
