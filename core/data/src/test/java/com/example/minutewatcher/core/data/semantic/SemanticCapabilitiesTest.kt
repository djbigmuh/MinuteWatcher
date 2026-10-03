package com.example.minutewatcher.core.data.semantic

import android.app.appsearch.AppSearchManager
import android.content.Context
import android.content.pm.PackageManager
import com.example.minutewatcher.core.common.util.PermissionManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class SemanticCapabilitiesTest {
    private val context: Context = mock()
    private val packageManager: PackageManager = mock()
    private val permissionManager: PermissionManager = mock()
    private val appSearchManager: AppSearchManager = mock()

    @Before
    fun setup() {
        whenever(context.packageManager).thenReturn(packageManager)
    }

    @Test
    @Config(sdk = [33]) // behavior-specific SDK
    fun `telephony not supported leads to tracking capabilities disabled`() {
        whenever(permissionManager.isTelephonySupported()).thenReturn(false)
        whenever(packageManager.hasSystemFeature("android.hardware.telephony.multisim")).thenReturn(false)

        val capabilities = SemanticCapabilities(context, permissionManager).getCapabilities()

        assertFalse(capabilities.find { it.type == "MINUTES_TRACKING" }!!.isSupported)
        assertFalse(capabilities.find { it.type == "DATA_TRACKING" }!!.isSupported)
        assertFalse(capabilities.find { it.type == "DUAL_SIM" }!!.isSupported)
    }

    @Test
    @Config(sdk = [33]) // behavior-specific SDK
    fun `telephony supported leads to tracking capabilities enabled`() {
        whenever(permissionManager.isTelephonySupported()).thenReturn(true)
        whenever(packageManager.hasSystemFeature("android.hardware.telephony.multisim")).thenReturn(true)

        val capabilities = SemanticCapabilities(context, permissionManager).getCapabilities()

        assertTrue(capabilities.find { it.type == "MINUTES_TRACKING" }!!.isSupported)
        assertTrue(capabilities.find { it.type == "DATA_TRACKING" }!!.isSupported)
        assertTrue(capabilities.find { it.type == "DUAL_SIM" }!!.isSupported)
    }

    @Test
    @Config(sdk = [33]) // behavior-specific SDK
    fun `API 26 features are always enabled`() {
        val capabilities = SemanticCapabilities(context, permissionManager).getCapabilities()

        assertTrue(capabilities.find { it.type == "DYNAMIC_SHORTCUTS" }!!.isSupported)
        assertTrue(capabilities.find { it.type == "QUICK_SETTINGS_TILE" }!!.isSupported)
    }

    @Test
    @Config(sdk = [33]) // behavior-specific SDK
    fun `API 33 app search service present leads to APP_SEARCH capability enabled`() {
        whenever(context.getSystemService(AppSearchManager::class.java)).thenReturn(appSearchManager)

        val capabilities = SemanticCapabilities(context, permissionManager).getCapabilities()

        assertTrue(capabilities.find { it.type == "APP_SEARCH" }!!.isSupported)
    }

    @Test
    @Config(sdk = [33]) // behavior-specific SDK
    fun `API 33 app search service missing leads to APP_SEARCH capability disabled`() {
        whenever(context.getSystemService(AppSearchManager::class.java)).thenReturn(null)

        val capabilities = SemanticCapabilities(context, permissionManager).getCapabilities()

        assertFalse(capabilities.find { it.type == "APP_SEARCH" }!!.isSupported)
    }

    @Test
    @Config(sdk = [28]) // behavior-specific SDK
    fun `API 28 app search capability is enabled without system service lookup`() {
        val capabilities = SemanticCapabilities(context, permissionManager).getCapabilities()

        assertTrue(capabilities.find { it.type == "APP_SEARCH" }!!.isSupported)
        verify(context, never()).getSystemService(AppSearchManager::class.java)
    }
}
