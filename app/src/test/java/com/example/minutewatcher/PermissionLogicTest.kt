package com.example.minutewatcher

import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.PermissionUiState
import com.example.minutewatcher.feature.main.util.TrackingBannerMapper
import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionLogicTest {
    @Test
    fun `TrackingBannerMapper uses phonePermissionPermanentlyDenied to determine DENIED state`() {
        val settings =
            AppSettings(
                phonePermissionPermanentlyDenied = true,
                onboardingCompleted = true,
            )

        val state =
            TrackingBannerMapper.mapToBannerState(
                statusMap = emptyMap(),
                phoneGranted = false,
                usageGranted = true,
                notificationsGranted = true,
                settings = settings,
            )

        assertEquals(PermissionUiState.PERMANENTLY_DENIED, state.phonePermissionState)
    }

    @Test
    fun `TrackingBannerMapper uses phonePermissionPermanentlyDenied to determine NOT_REQUESTED state`() {
        val settings =
            AppSettings(
                phonePermissionPermanentlyDenied = false,
                onboardingCompleted = true,
            )

        val state =
            TrackingBannerMapper.mapToBannerState(
                statusMap = emptyMap(),
                phoneGranted = false,
                usageGranted = true,
                notificationsGranted = true,
                settings = settings,
            )

        assertEquals(PermissionUiState.NOT_REQUESTED, state.phonePermissionState)
    }

    @Test
    fun `notification permission state is NOT_REQUESTED when not permanently denied`() {
        val settings =
            AppSettings(
                onboardingCompleted = true,
                notifyLowMinutes = true,
                notificationPermissionPermanentlyDenied = false,
            )

        val state =
            TrackingBannerMapper.mapToBannerState(
                statusMap = emptyMap(),
                phoneGranted = false,
                usageGranted = true,
                notificationsGranted = false,
                settings = settings,
            )

        assertEquals(PermissionUiState.NOT_REQUESTED, state.notificationPermissionState)
    }

    @Test
    fun `notification permission state is PERMANENTLY_DENIED when permanently denied`() {
        val settings =
            AppSettings(
                onboardingCompleted = true,
                notifyLowMinutes = true,
                notificationPermissionPermanentlyDenied = true,
            )

        val state =
            TrackingBannerMapper.mapToBannerState(
                statusMap = emptyMap(),
                phoneGranted = false,
                usageGranted = true,
                notificationsGranted = false,
                settings = settings,
            )

        assertEquals(PermissionUiState.PERMANENTLY_DENIED, state.notificationPermissionState)
    }

    @Test
    fun `notification permission state is GRANTED when both notification alerts are disabled`() {
        val settings =
            AppSettings(
                onboardingCompleted = true,
                notifyLowMinutes = false,
                notifyLowTraffic = false,
            )

        val state =
            TrackingBannerMapper.mapToBannerState(
                statusMap = emptyMap(),
                phoneGranted = false,
                usageGranted = true,
                notificationsGranted = false,
                settings = settings,
            )

        assertEquals(PermissionUiState.GRANTED, state.notificationPermissionState)
    }
}
