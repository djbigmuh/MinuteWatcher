package com.example.minutewatcher.feature.main.util

import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.PermissionUiState
import com.example.minutewatcher.core.model.TrackingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrackingBannerMapperTest {
    @Test
    fun `mapToBannerState returns GRANTED when all permissions are granted`() {
        val state =
            TrackingBannerMapper.mapToBannerState(
                statusMap = emptyMap(),
                phoneGranted = true,
                usageGranted = true,
                notificationsGranted = true,
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
                settings = AppSettings(wasPhonePermissionRequestedBefore = false, onboardingCompleted = true),
            )
        assertEquals(PermissionUiState.NOT_REQUESTED, state.phonePermissionState)

        // Case: Denied (Requested but not granted)
        state =
            TrackingBannerMapper.mapToBannerState(
                statusMap = emptyMap(),
                phoneGranted = false,
                usageGranted = true,
                notificationsGranted = true,
                settings = AppSettings(wasPhonePermissionRequestedBefore = true, onboardingCompleted = true),
            )
        assertEquals(PermissionUiState.PERMANENTLY_DENIED, state.phonePermissionState) // Internally derived

        // Case: Granted
        state =
            TrackingBannerMapper.mapToBannerState(
                statusMap = emptyMap(),
                phoneGranted = true,
                usageGranted = true,
                notificationsGranted = true,
                settings = AppSettings(wasPhonePermissionRequestedBefore = true, onboardingCompleted = true),
            )
        assertEquals(PermissionUiState.GRANTED, state.phonePermissionState)
    }

    @Test
    fun `mapToBannerState shows usage access banner when not granted`() {
        val state =
            TrackingBannerMapper.mapToBannerState(
                statusMap = emptyMap(),
                phoneGranted = true,
                usageGranted = false,
                notificationsGranted = true,
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
                settings = AppSettings(onboardingCompleted = true),
            )

        assertFalse(state.showTrafficErrorBanner)
        assertTrue(state.showUsageAccessBanner)
    }

    @Test
    fun `mapToBannerState reflects notification permission based on settings`() {
        // Case: Notifications granted (always GRANTED)
        var state =
            TrackingBannerMapper.mapToBannerState(
                statusMap = emptyMap(),
                phoneGranted = true,
                usageGranted = true,
                notificationsGranted = true,
                settings = AppSettings(onboardingCompleted = true, notificationPermissionRequested = true),
            )
        assertEquals(PermissionUiState.GRANTED, state.notificationPermissionState)

        // Case: Not granted, requested before -> PERMANENTLY_DENIED (derived internally)
        state =
            TrackingBannerMapper.mapToBannerState(
                statusMap = emptyMap(),
                phoneGranted = true,
                usageGranted = true,
                notificationsGranted = false,
                settings = AppSettings(onboardingCompleted = true, notificationPermissionRequested = true),
            )
        assertEquals(PermissionUiState.PERMANENTLY_DENIED, state.notificationPermissionState)

        // Case: Not granted, NOT requested before -> NOT_REQUESTED
        state =
            TrackingBannerMapper.mapToBannerState(
                statusMap = emptyMap(),
                phoneGranted = true,
                usageGranted = true,
                notificationsGranted = false,
                settings = AppSettings(onboardingCompleted = true, notificationPermissionRequested = false),
            )
        assertEquals(PermissionUiState.NOT_REQUESTED, state.notificationPermissionState)
    }

    @Test
    fun `mapToBannerState reflects notification permission even before onboarding completed`() {
        val state =
            TrackingBannerMapper.mapToBannerState(
                statusMap = emptyMap(),
                phoneGranted = true,
                usageGranted = true,
                notificationsGranted = false,
                settings =
                    AppSettings(
                        onboardingCompleted = false,
                        notifyLowMinutes = true,
                        notificationPermissionRequested = true,
                    ),
            )

        assertEquals(PermissionUiState.PERMANENTLY_DENIED, state.notificationPermissionState)
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
                settings = AppSettings(onboardingCompleted = false, wasPhonePermissionRequestedBefore = true),
            )

        assertEquals(PermissionUiState.PERMANENTLY_DENIED, state.phonePermissionState)
        assertFalse(state.showUsageAccessBanner) // Should be suppressed
        assertEquals(PermissionUiState.NOT_REQUESTED, state.notificationPermissionState) // Default
    }

    @Test
    fun `mapToBannerState shows errors even before onboarding completed`() {
        val state =
            TrackingBannerMapper.mapToBannerState(
                statusMap =
                    mapOf(
                        "traffic" to TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN),
                        "widget_observation" to TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR),
                    ),
                phoneGranted = true,
                usageGranted = false,
                notificationsGranted = false,
                settings = AppSettings(onboardingCompleted = false),
                isStorageDegraded = true,
            )

        assertTrue(state.showTrafficErrorBanner)
        assertTrue(state.observationFailed)
        assertTrue(state.isStorageDegraded)
        assertFalse(state.showUsageAccessBanner) // Still suppressed
    }

    @Test
    fun `mapToBannerState handles AppSearch DATABASE_ERROR retry limit`() {
        // Case: DATABASE_ERROR shown initially
        val baseStatus = mapOf("appsearch" to TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR))
        var state = mapToBannerStateWithAppSearch(baseStatus, retryCount = 0)
        assertTrue(state.showAppSearchErrorBanner)

        // Case: DATABASE_ERROR hidden after 3 retries
        state = mapToBannerStateWithAppSearch(baseStatus, retryCount = 3)
        assertFalse(state.showAppSearchErrorBanner)
    }

    @Test
    fun `mapToBannerState suppresses AppSearch NOT_SUPPORTED error`() {
        val status = mapOf("appsearch" to TrackingStatus.Error(TrackingStatus.ErrorType.NOT_SUPPORTED))
        val state = mapToBannerStateWithAppSearch(status)
        assertFalse(state.showAppSearchErrorBanner)
    }

    @Test
    fun `mapToBannerState handles AppSearch dismissal`() {
        val status = mapOf("appsearch" to TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN))

        // Initially shown
        var state = mapToBannerStateWithAppSearch(status, dismissed = false)
        assertTrue(state.showAppSearchErrorBanner)

        // Hidden after dismissal
        state = mapToBannerStateWithAppSearch(status, dismissed = true)
        assertFalse(state.showAppSearchErrorBanner)
    }

    @Test
    fun `mapToBannerState reflects observationFailed state`() {
        val state =
            TrackingBannerMapper.mapToBannerState(
                statusMap =
                    mapOf(
                        "widget_observation" to
                            TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR),
                    ),
                phoneGranted = true,
                usageGranted = true,
                notificationsGranted = true,
                settings = AppSettings(onboardingCompleted = true),
            )

        assertTrue(state.observationFailed)
    }

    @Test
    fun `mapToBannerState returns GRANTED when notifications are disabled in settings`() {
        val state =
            TrackingBannerMapper.mapToBannerState(
                statusMap = emptyMap(),
                phoneGranted = true,
                usageGranted = true,
                notificationsGranted = false,
                settings =
                    AppSettings(
                        onboardingCompleted = true,
                        notifyLowMinutes = false,
                        notifyLowTraffic = false,
                    ),
            )

        assertEquals(PermissionUiState.GRANTED, state.notificationPermissionState)
    }

    @Test
    fun `mapToBannerState hides all banners when statusMap is empty and permissions granted`() {
        val state =
            TrackingBannerMapper.mapToBannerState(
                statusMap = emptyMap(),
                phoneGranted = true,
                usageGranted = true,
                notificationsGranted = true,
                settings = AppSettings(onboardingCompleted = true),
            )

        assertFalse(state.showTrafficErrorBanner)
        assertFalse(state.showTrafficFallbackBanner)
        assertFalse(state.showCallsErrorBanner)
        assertFalse(state.showAppSearchErrorBanner)
        assertFalse(state.showShortcutsErrorBanner)
        assertFalse(state.showUsageAccessBanner)
        assertEquals(PermissionUiState.GRANTED, state.phonePermissionState)
        assertEquals(PermissionUiState.GRANTED, state.notificationPermissionState)
        assertFalse(state.observationFailed)
    }

    private fun mapToBannerStateWithAppSearch(
        statusMap: Map<String, TrackingStatus>,
        retryCount: Int = 0,
        dismissed: Boolean = false,
    ) = TrackingBannerMapper.mapToBannerState(
        statusMap = statusMap,
        phoneGranted = true,
        usageGranted = true,
        notificationsGranted = true,
        settings = AppSettings(onboardingCompleted = true),
        appSearchRetryCount = retryCount,
        appSearchBannerDismissed = dismissed,
    )
}
