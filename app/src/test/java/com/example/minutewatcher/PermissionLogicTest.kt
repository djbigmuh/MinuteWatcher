package com.example.minutewatcher

import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.PermissionUiState
import com.example.minutewatcher.feature.main.util.TrackingBannerMapper
import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionLogicTest {
    @Test
    fun `TrackingBannerMapper uses wasPhonePermissionRequestedBefore to determine DENIED state`() {
        val settings =
            AppSettings(
                wasPhonePermissionRequestedBefore = true,
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
    fun `TrackingBannerMapper uses wasPhonePermissionRequestedBefore to determine NOT_REQUESTED state`() {
        val settings =
            AppSettings(
                wasPhonePermissionRequestedBefore = false,
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
    fun `notification permission state is NOT_REQUESTED when notifyLowMinutes is enabled and not requested before`() {
        val settings =
            AppSettings(
                onboardingCompleted = true,
                notifyLowMinutes = true,
                notificationPermissionRequested = false,
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
    fun `notification permission state is PERMANENTLY_DENIED when notifyLowMinutes is enabled and requested before`() {
        val settings =
            AppSettings(
                onboardingCompleted = true,
                notifyLowMinutes = true,
                notificationPermissionRequested = true,
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
