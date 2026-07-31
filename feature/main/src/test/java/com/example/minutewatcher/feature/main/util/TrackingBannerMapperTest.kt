package com.example.minutewatcher.feature.main.util

import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.PermissionUiState
import com.example.minutewatcher.core.model.TrackingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingBannerMapperTest {
    @Test
    fun `mapToBannerState returns GRANTED when all permissions are granted`() {
        val state =
            TrackingBannerMapper.mapToBannerState(
                statusMap = emptyMap(),
                phoneGranted = true,
                usageGranted = true,
                notificationsGranted = true,
                phonePermanentlyDenied = false,
                notificationPermanentlyDenied = false,
                settings = AppSettings(),
            )

        assertEquals(PermissionUiState.GRANTED, state.phonePermissionState)
        assertEquals(PermissionUiState.GRANTED, state.notificationPermissionState)
        assertFalse(state.showUsageAccessBanner)
        assertFalse(state.showTrafficErrorBanner)
    }

    @Test
    fun `mapToBannerState reflects phone permission states`() {
        // Case: Not requested
        var state =
            TrackingBannerMapper.mapToBannerState(
                statusMap = emptyMap(),
                phoneGranted = false,
                usageGranted = true,
                notificationsGranted = true,
                phonePermanentlyDenied = false,
                notificationPermanentlyDenied = false,
                settings = AppSettings(phonePermissionRequested = false),
            )
        assertEquals(PermissionUiState.NOT_REQUESTED, state.phonePermissionState)

        // Case: Denied
        state =
            TrackingBannerMapper.mapToBannerState(
                statusMap = emptyMap(),
                phoneGranted = false,
                usageGranted = true,
                notificationsGranted = true,
                phonePermanentlyDenied = false,
                notificationPermanentlyDenied = false,
                settings = AppSettings(phonePermissionRequested = true),
            )
        assertEquals(PermissionUiState.DENIED, state.phonePermissionState)

        // Case: Permanently Denied
        state =
            TrackingBannerMapper.mapToBannerState(
                statusMap = emptyMap(),
                phoneGranted = false,
                usageGranted = true,
                notificationsGranted = true,
                phonePermanentlyDenied = true,
                notificationPermanentlyDenied = false,
                settings = AppSettings(phonePermissionRequested = true),
            )
        assertEquals(PermissionUiState.PERMANENTLY_DENIED, state.phonePermissionState)
    }

    @Test
    fun `mapToBannerState shows usage access banner when not granted`() {
        val state =
            TrackingBannerMapper.mapToBannerState(
                statusMap = emptyMap(),
                phoneGranted = true,
                usageGranted = false,
                notificationsGranted = true,
                phonePermanentlyDenied = false,
                notificationPermanentlyDenied = false,
                settings = AppSettings(),
            )

        assertTrue(state.showUsageAccessBanner)
    }

    @Test
    fun `mapToBannerState shows traffic error banner for non-permission errors`() {
        val state =
            TrackingBannerMapper.mapToBannerState(
                statusMap = mapOf("traffic" to TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN)),
                phoneGranted = true,
                usageGranted = true,
                notificationsGranted = true,
                phonePermanentlyDenied = false,
                notificationPermanentlyDenied = false,
                settings = AppSettings(),
            )

        assertTrue(state.showTrafficErrorBanner)
        assertFalse(state.showUsageAccessBanner)
    }

    @Test
    fun `mapToBannerState suppresses traffic error banner when permission is missing`() {
        // When permission is missing, we show the Usage Access banner instead
        val state =
            TrackingBannerMapper.mapToBannerState(
                statusMap = mapOf("traffic" to TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING)),
                phoneGranted = true,
                usageGranted = false,
                notificationsGranted = true,
                phonePermanentlyDenied = false,
                notificationPermanentlyDenied = false,
                settings = AppSettings(),
            )

        assertFalse(state.showTrafficErrorBanner)
        assertTrue(state.showUsageAccessBanner)
    }

    @Test
    fun `mapToBannerState reflects notification permission based on settings`() {
        // Enabled in settings, but not granted
        var state =
            TrackingBannerMapper.mapToBannerState(
                statusMap = emptyMap(),
                phoneGranted = true,
                usageGranted = true,
                notificationsGranted = false,
                phonePermanentlyDenied = false,
                notificationPermanentlyDenied = false,
                settings = AppSettings(notifyLowMinutes = true),
            )
        assertNotEquals(PermissionUiState.GRANTED, state.notificationPermissionState)

        // Disabled in settings, should report GRANTED (suppressed)
        state =
            TrackingBannerMapper.mapToBannerState(
                statusMap = emptyMap(),
                phoneGranted = true,
                usageGranted = true,
                notificationsGranted = false,
                phonePermanentlyDenied = false,
                notificationPermanentlyDenied = false,
                settings = AppSettings(notifyLowMinutes = false, notifyLowTraffic = false),
            )
        assertEquals(PermissionUiState.GRANTED, state.notificationPermissionState)
    }
}
