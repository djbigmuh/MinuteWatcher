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
    fun `TrackingBannerMapper uses permanentlyDenied flag`() {
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
}
