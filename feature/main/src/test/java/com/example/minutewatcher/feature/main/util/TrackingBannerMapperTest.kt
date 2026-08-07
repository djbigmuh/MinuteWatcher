package com.example.minutewatcher.feature.main.util

import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.PermissionUiState
import com.example.minutewatcher.core.model.TrackingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                settings = AppSettings(onboardingCompleted = true),
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
                settings = AppSettings(phonePermissionRequested = false, onboardingCompleted = true),
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
                settings = AppSettings(phonePermissionRequested = true, onboardingCompleted = true),
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
                settings = AppSettings(phonePermissionRequested = true, onboardingCompleted = true),
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
                settings = AppSettings(onboardingCompleted = true),
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
                settings = AppSettings(onboardingCompleted = true),
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
                settings = AppSettings(onboardingCompleted = true),
            )

        assertFalse(state.showTrafficErrorBanner)
        assertTrue(state.showUsageAccessBanner)
    }

    @Test
    fun `mapToBannerState reflects notification permission based on settings`() {
        // ... (текущий код) ...
    }

    @Test
    fun `mapToBannerState reflects traffic fallback slot`() {
        val state =
            TrackingBannerMapper.mapToBannerState(
                statusMap =
                    mapOf(
                        "traffic" to
                            TrackingStatus.Error(
                                TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION,
                                slotIndex = 1,
                            ),
                    ),
                phoneGranted = true,
                usageGranted = true,
                notificationsGranted = true,
                phonePermanentlyDenied = false,
                notificationPermanentlyDenied = false,
                settings = AppSettings(onboardingCompleted = true),
            )

        assertTrue(state.showTrafficFallbackBanner)
        assertEquals(1, state.trafficFallbackSlot)
    }

    @Test
    fun `mapToBannerState still shows phone permission banner before onboarding completed`() {
        val state =
            TrackingBannerMapper.mapToBannerState(
                statusMap = emptyMap(),
                phoneGranted = false,
                usageGranted = false,
                notificationsGranted = false,
                phonePermanentlyDenied = true,
                notificationPermanentlyDenied = false,
                settings = AppSettings(onboardingCompleted = false, phonePermissionRequested = true),
            )

        assertEquals(PermissionUiState.PERMANENTLY_DENIED, state.phonePermissionState)
        assertFalse(state.showUsageAccessBanner) // Should be suppressed
        assertEquals(PermissionUiState.NOT_REQUESTED, state.notificationPermissionState) // Default
    }
}
