package com.github.djbigmuh.minutewatcher.core.data.semantic

import android.content.Context
import android.content.pm.PackageManager
import com.github.djbigmuh.minutewatcher.core.common.util.PermissionManager
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SemanticCapabilitiesTest {
    private val context: Context = mock()
    private val packageManager: PackageManager = mock()
    private val permissionManager: PermissionManager = mock()

    @Before
    fun setup() {
        whenever(context.packageManager).thenReturn(packageManager)
    }

    @Test
    fun `telephony not supported leads to tracking capabilities disabled`() {
        whenever(permissionManager.isTelephonySupported()).thenReturn(false)
        whenever(packageManager.hasSystemFeature("android.hardware.telephony.multisim")).thenReturn(false)

        val capabilities = SemanticCapabilities(context, permissionManager).getCapabilities()
        val capMap = capabilities.associate { it.type to it.isSupported }

        assertEquals(false, capMap["MINUTES_TRACKING"])
        assertEquals(false, capMap["DATA_TRACKING"])
        assertEquals(false, capMap["DUAL_SIM"])
    }

    @Test
    fun `telephony supported leads to tracking capabilities enabled`() {
        whenever(permissionManager.isTelephonySupported()).thenReturn(true)
        whenever(packageManager.hasSystemFeature("android.hardware.telephony.multisim")).thenReturn(true)

        val capabilities = SemanticCapabilities(context, permissionManager).getCapabilities()
        val capMap = capabilities.associate { it.type to it.isSupported }

        assertEquals(true, capMap["MINUTES_TRACKING"])
        assertEquals(true, capMap["DATA_TRACKING"])
        assertEquals(true, capMap["DUAL_SIM"])
    }

    @Test
    fun `API 26 features are always enabled`() {
        val capabilities = SemanticCapabilities(context, permissionManager).getCapabilities()
        val capMap = capabilities.associate { it.type to it.isSupported }

        assertEquals(true, capMap["DYNAMIC_SHORTCUTS"])
        assertEquals(true, capMap["QUICK_SETTINGS_TILE"])
    }

    @Test
    fun `app search capability is enabled using bundled local storage without system service lookup`() {
        val capabilities = SemanticCapabilities(context, permissionManager).getCapabilities()
        val capMap = capabilities.associate { it.type to it.isSupported }

        assertEquals(true, capMap["APP_SEARCH"])
        verify(context, never()).getSystemService(any<Class<*>>())
    }
}
