package host.serv.djbigmuh.minutewatcher.feature.main.util

import host.serv.djbigmuh.minutewatcher.core.model.AppSettings
import host.serv.djbigmuh.minutewatcher.core.model.Constants
import host.serv.djbigmuh.minutewatcher.core.model.PermissionUiState
import host.serv.djbigmuh.minutewatcher.core.model.TrackingKey
import host.serv.djbigmuh.minutewatcher.core.model.TrackingStatus
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
                settings = AppSettings(onboardingCompleted = true),
            )

        assertEquals(PermissionUiState.GRANTED, state.phonePermissionState)
        assertEquals(PermissionUiState.GRANTED, state.notificationPermissionState)
        assertFalse(state.showUsageAccessBanner)
        assertFalse(state.showTrafficErrorBanner)
    }

    @Test
    fun `mapToBannerState reflects phone permission states`() {
        // Case: Not requested (permanentlyDenied = false)
        var state =
            TrackingBannerMapper.mapToBannerState(
                statusMap = emptyMap(),
                phoneGranted = false,
                usageGranted = true,
                notificationsGranted = true,
                settings = AppSettings(phonePermissionPermanentlyDenied = false, onboardingCompleted = true),
            )
        assertEquals(PermissionUiState.NOT_REQUESTED, state.phonePermissionState)

        // Case: Permanently denied (phoneGranted = false, phonePermissionPermanentlyDenied = true)
        state =
            TrackingBannerMapper.mapToBannerState(
                statusMap = emptyMap(),
                phoneGranted = false,
                usageGranted = true,
                notificationsGranted = true,
                settings = AppSettings(phonePermissionPermanentlyDenied = true, onboardingCompleted = true),
            )
        assertEquals(PermissionUiState.PERMANENTLY_DENIED, state.phonePermissionState)

        // Case: Granted (phoneGranted = true, phonePermissionPermanentlyDenied = true)
        state =
            TrackingBannerMapper.mapToBannerState(
                statusMap = emptyMap(),
                phoneGranted = true,
                usageGranted = true,
                notificationsGranted = true,
                settings = AppSettings(phonePermissionPermanentlyDenied = true, onboardingCompleted = true),
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
                statusMap = mapOf(TrackingKey.TRAFFIC to TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN)),
                phoneGranted = true,
                usageGranted = true,
                notificationsGranted = true,
                settings = AppSettings(onboardingCompleted = true),
            )

        assertTrue(state.showTrafficErrorBanner)
        assertFalse(state.showUsageAccessBanner)
    }

    @Test
    fun `mapToBannerState shows storage degraded banner for USAGE_STATE database error`() {
        val state =
            TrackingBannerMapper.mapToBannerState(
                statusMap =
                    mapOf(
                        TrackingKey.USAGE_STATE to TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR),
                    ),
                phoneGranted = true,
                usageGranted = true,
                notificationsGranted = true,
                settings = AppSettings(onboardingCompleted = true),
            )

        assertTrue(state.observationFailed)
    }

    @Test
    fun `mapToBannerState suppresses traffic error banner when permission is missing`() {
        // When permission is missing, we show the Usage Access banner instead
        val state =
            TrackingBannerMapper.mapToBannerState(
                statusMap =
                    mapOf(
                        TrackingKey.TRAFFIC to
                            TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING),
                    ),
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
                settings = AppSettings(onboardingCompleted = true, notificationPermissionPermanentlyDenied = true),
            )
        assertEquals(PermissionUiState.GRANTED, state.notificationPermissionState)

        // Case: Not granted, permanently denied -> PERMANENTLY_DENIED
        state =
            TrackingBannerMapper.mapToBannerState(
                statusMap = emptyMap(),
                phoneGranted = true,
                usageGranted = true,
                notificationsGranted = false,
                settings = AppSettings(onboardingCompleted = true, notificationPermissionPermanentlyDenied = true),
            )
        assertEquals(PermissionUiState.PERMANENTLY_DENIED, state.notificationPermissionState)

        // Case: Not granted, NOT permanently denied -> NOT_REQUESTED
        state =
            TrackingBannerMapper.mapToBannerState(
                statusMap = emptyMap(),
                phoneGranted = true,
                usageGranted = true,
                notificationsGranted = false,
                settings = AppSettings(onboardingCompleted = true, notificationPermissionPermanentlyDenied = false),
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
                        notificationPermissionPermanentlyDenied = true,
                    ),
            )

        assertEquals(PermissionUiState.PERMANENTLY_DENIED, state.notificationPermissionState)
    }

    @Test
    fun `mapToBannerState still shows phone permission banner before onboarding completed`() {
        val state =
            TrackingBannerMapper.mapToBannerState(
                statusMap = emptyMap(),
                phoneGranted = false,
                usageGranted = false,
                notificationsGranted = false,
                settings = AppSettings(onboardingCompleted = false, phonePermissionPermanentlyDenied = true),
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
                        TrackingKey.TRAFFIC to TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN),
                        TrackingKey.WIDGET_OBSERVATION to TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR),
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
        val baseStatus = mapOf(TrackingKey.APPSEARCH to TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR))
        var state = mapToBannerStateWithAppSearch(baseStatus, retryCount = 0)
        assertTrue(state.showAppSearchErrorBanner)

        // Case: DATABASE_ERROR hidden after 3 retries
        state = mapToBannerStateWithAppSearch(baseStatus, retryCount = 3)
        assertFalse(state.showAppSearchErrorBanner)
    }

    @Test
    fun `mapToBannerState suppresses AppSearch NOT_SUPPORTED error`() {
        val status = mapOf(TrackingKey.APPSEARCH to TrackingStatus.Error(TrackingStatus.ErrorType.NOT_SUPPORTED))
        val state = mapToBannerStateWithAppSearch(status)
        assertFalse(state.showAppSearchErrorBanner)
    }

    @Test
    fun `mapToBannerState handles AppSearch dismissal`() {
        val status = mapOf(TrackingKey.APPSEARCH to TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN))

        // Initially shown
        var state = mapToBannerStateWithAppSearch(status, dismissed = false)
        assertTrue(state.showAppSearchErrorBanner)

        // Hidden after dismissal
        state = mapToBannerStateWithAppSearch(status, dismissed = true)
        assertFalse(state.showAppSearchErrorBanner)
    }

    @Test
    fun `mapToBannerState reflects observationFailed state`() {
        // Case 1: Database error on observation
        var state =
            TrackingBannerMapper.mapToBannerState(
                statusMap =
                    mapOf(
                        TrackingKey.WIDGET_OBSERVATION to
                            TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR),
                    ),
                phoneGranted = true,
                usageGranted = true,
                notificationsGranted = true,
                settings = AppSettings(onboardingCompleted = true),
            )
        assertTrue(state.observationFailed)

        // Case 2: Size limit error (should NOT mark observation as failed, as it's a known non-retryable limit)
        state =
            TrackingBannerMapper.mapToBannerState(
                statusMap =
                    mapOf(
                        TrackingKey.WIDGET_OBSERVATION to
                            TrackingStatus.Error(
                                TrackingStatus.ErrorType.NOT_SUPPORTED,
                                code = TrackingStatus.ErrorCode.WIDGET_SIZE_LIMIT,
                            ),
                    ),
                phoneGranted = true,
                usageGranted = true,
                notificationsGranted = true,
                settings = AppSettings(onboardingCompleted = true),
            )
        assertFalse(state.observationFailed)
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
        assertFalse(state.showCallsErrorBanner)
        assertFalse(state.showAppSearchErrorBanner)
        assertFalse(state.showShortcutsErrorBanner)
        assertFalse(state.showUsageAccessBanner)
        assertEquals(PermissionUiState.GRANTED, state.phonePermissionState)
        assertEquals(PermissionUiState.GRANTED, state.notificationPermissionState)
        assertFalse(state.observationFailed)
    }

    @Test
    fun `mapToBannerState shows sim discovery error for unknown and database errors`() {
        val state1 =
            TrackingBannerMapper.mapToBannerState(
                statusMap = mapOf(TrackingKey.SIM_DISCOVERY to TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN)),
                phoneGranted = true,
                usageGranted = true,
                notificationsGranted = true,
                settings = AppSettings(onboardingCompleted = true),
            )
        assertTrue(state1.showSimDiscoveryErrorBanner)

        val state2 =
            TrackingBannerMapper.mapToBannerState(
                statusMap =
                    mapOf(
                        TrackingKey.SIM_DISCOVERY to TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR),
                    ),
                phoneGranted = true,
                usageGranted = true,
                notificationsGranted = true,
                settings = AppSettings(onboardingCompleted = true),
            )
        assertTrue(state2.showSimDiscoveryErrorBanner)
    }

    @Test
    fun `mapToBannerState suppresses sim discovery error for permission missing and not supported`() {
        val state1 =
            TrackingBannerMapper.mapToBannerState(
                statusMap =
                    mapOf(
                        TrackingKey.SIM_DISCOVERY to TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING),
                    ),
                phoneGranted = true,
                usageGranted = true,
                notificationsGranted = true,
                settings = AppSettings(onboardingCompleted = true),
            )
        assertFalse(state1.showSimDiscoveryErrorBanner)

        val state2 =
            TrackingBannerMapper.mapToBannerState(
                statusMap =
                    mapOf(
                        TrackingKey.SIM_DISCOVERY to TrackingStatus.Error(TrackingStatus.ErrorType.NOT_SUPPORTED),
                    ),
                phoneGranted = true,
                usageGranted = true,
                notificationsGranted = true,
                settings = AppSettings(onboardingCompleted = true),
            )
        assertFalse(state2.showSimDiscoveryErrorBanner)
    }

    @Test
    fun `mapToBannerState suppresses sim discovery error for active idle or absent status`() {
        val state1 =
            TrackingBannerMapper.mapToBannerState(
                statusMap = mapOf(TrackingKey.SIM_DISCOVERY to TrackingStatus.Active),
                phoneGranted = true,
                usageGranted = true,
                notificationsGranted = true,
                settings = AppSettings(onboardingCompleted = true),
            )
        assertFalse(state1.showSimDiscoveryErrorBanner)

        val state2 =
            TrackingBannerMapper.mapToBannerState(
                statusMap = mapOf(TrackingKey.SIM_DISCOVERY to TrackingStatus.Idle),
                phoneGranted = true,
                usageGranted = true,
                notificationsGranted = true,
                settings = AppSettings(onboardingCompleted = true),
            )
        assertFalse(state2.showSimDiscoveryErrorBanner)

        val state3 =
            TrackingBannerMapper.mapToBannerState(
                statusMap = emptyMap(),
                phoneGranted = true,
                usageGranted = true,
                notificationsGranted = true,
                settings = AppSettings(onboardingCompleted = true),
            )
        assertFalse(state3.showSimDiscoveryErrorBanner)
    }

    @Test
    fun `mapToBannerState shows sim discovery error with incomplete onboarding`() {
        val state =
            TrackingBannerMapper.mapToBannerState(
                statusMap = mapOf(TrackingKey.SIM_DISCOVERY to TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN)),
                phoneGranted = true,
                usageGranted = true,
                notificationsGranted = true,
                settings = AppSettings(onboardingCompleted = false),
            )
        assertTrue(state.showSimDiscoveryErrorBanner)
    }

    @Test
    fun `mapToBannerState handles CALLS attribution and regular error banners`() {
        // INACCURATE_ATTRIBUTION -> attribution error true, calls error false
        val state1 =
            TrackingBannerMapper.mapToBannerState(
                statusMap =
                    mapOf(
                        TrackingKey.CALLS to TrackingStatus.Error(TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION),
                    ),
                phoneGranted = true,
                usageGranted = true,
                notificationsGranted = true,
                settings = AppSettings(onboardingCompleted = true),
            )
        assertTrue(state1.showCallsAttributionErrorBanner)
        assertFalse(state1.showCallsErrorBanner)

        // UNKNOWN -> calls error true, attribution error false
        val state2 =
            TrackingBannerMapper.mapToBannerState(
                statusMap = mapOf(TrackingKey.CALLS to TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN)),
                phoneGranted = true,
                usageGranted = true,
                notificationsGranted = true,
                settings = AppSettings(onboardingCompleted = true),
            )
        assertFalse(state2.showCallsAttributionErrorBanner)
        assertTrue(state2.showCallsErrorBanner)

        // PERMISSION_MISSING -> both false
        val state3 =
            TrackingBannerMapper.mapToBannerState(
                statusMap =
                    mapOf(
                        TrackingKey.CALLS to TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING),
                    ),
                phoneGranted = true,
                usageGranted = true,
                notificationsGranted = true,
                settings = AppSettings(onboardingCompleted = true),
            )
        assertFalse(state3.showCallsAttributionErrorBanner)
        assertFalse(state3.showCallsErrorBanner)

        // Active / Idle / absent -> both false
        val state4 =
            TrackingBannerMapper.mapToBannerState(
                statusMap = mapOf(TrackingKey.CALLS to TrackingStatus.Active),
                phoneGranted = true,
                usageGranted = true,
                notificationsGranted = true,
                settings = AppSettings(onboardingCompleted = true),
            )
        assertFalse(state4.showCallsAttributionErrorBanner)
        assertFalse(state4.showCallsErrorBanner)
    }

    @Test
    fun `mapToBannerState handles CALLS threshold and incomplete onboarding banners`() {
        // inaccurateCallAttributionsCount at threshold -> showInaccurateCallsBanner == true
        val state5 =
            TrackingBannerMapper.mapToBannerState(
                statusMap = emptyMap(),
                phoneGranted = true,
                usageGranted = true,
                notificationsGranted = true,
                settings =
                    AppSettings(
                        onboardingCompleted = true,
                        inaccurateCallAttributionsCount = Constants.Calls.INACCURATE_ATTRIBUTION_BANNER_THRESHOLD,
                    ),
            )
        assertTrue(state5.showInaccurateCallsBanner)
        assertFalse(state5.showCallsAttributionErrorBanner)

        // Incomplete onboarding populates flags correctly
        val state6 =
            TrackingBannerMapper.mapToBannerState(
                statusMap =
                    mapOf(
                        TrackingKey.CALLS to TrackingStatus.Error(TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION),
                    ),
                phoneGranted = true,
                usageGranted = true,
                notificationsGranted = true,
                settings = AppSettings(onboardingCompleted = false),
            )
        assertTrue(state6.showCallsAttributionErrorBanner)
    }

    @Test
    fun `mapToBannerState observationFailed on NOT_SUPPORTED widget and DATABASE_ERROR settings`() {
        val state =
            TrackingBannerMapper.mapToBannerState(
                statusMap =
                    mapOf(
                        TrackingKey.WIDGET_OBSERVATION to
                            TrackingStatus.Error(
                                TrackingStatus.ErrorType.NOT_SUPPORTED,
                                code = TrackingStatus.ErrorCode.WIDGET_SIZE_LIMIT,
                            ),
                        TrackingKey.APP_SETTINGS to
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
    fun `mapToBannerState observationFailed on NOT_SUPPORTED widget and DATABASE_ERROR usage`() {
        val state =
            TrackingBannerMapper.mapToBannerState(
                statusMap =
                    mapOf(
                        TrackingKey.WIDGET_OBSERVATION to
                            TrackingStatus.Error(
                                TrackingStatus.ErrorType.NOT_SUPPORTED,
                                code = TrackingStatus.ErrorCode.WIDGET_SIZE_LIMIT,
                            ),
                        TrackingKey.USAGE_STATE to
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
    fun `mapToBannerState observationFailed false on widget DATABASE_ERROR with WIDGET_SIZE_LIMIT`() {
        val state =
            TrackingBannerMapper.mapToBannerState(
                statusMap =
                    mapOf(
                        TrackingKey.WIDGET_OBSERVATION to
                            TrackingStatus.Error(
                                TrackingStatus.ErrorType.DATABASE_ERROR,
                                code = TrackingStatus.ErrorCode.WIDGET_SIZE_LIMIT,
                            ),
                    ),
                phoneGranted = true,
                usageGranted = true,
                notificationsGranted = true,
                settings = AppSettings(onboardingCompleted = true),
            )
        assertFalse(state.observationFailed)
    }

    @Test
    fun `mapToBannerState observationFailed true on widget DATABASE_ERROR without code`() {
        val state =
            TrackingBannerMapper.mapToBannerState(
                statusMap =
                    mapOf(
                        TrackingKey.WIDGET_OBSERVATION to
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
    fun `mapToBannerState observationFailed false on widget NOT_SUPPORTED with WIDGET_SIZE_LIMIT`() {
        val state =
            TrackingBannerMapper.mapToBannerState(
                statusMap =
                    mapOf(
                        TrackingKey.WIDGET_OBSERVATION to
                            TrackingStatus.Error(
                                TrackingStatus.ErrorType.NOT_SUPPORTED,
                                code = TrackingStatus.ErrorCode.WIDGET_SIZE_LIMIT,
                            ),
                    ),
                phoneGranted = true,
                usageGranted = true,
                notificationsGranted = true,
                settings = AppSettings(onboardingCompleted = true),
            )
        assertFalse(state.observationFailed)
    }

    @Test
    fun `mapToBannerState observationFailed true on isStorageDegraded true with empty map`() {
        val state =
            TrackingBannerMapper.mapToBannerState(
                statusMap = emptyMap(),
                phoneGranted = true,
                usageGranted = true,
                notificationsGranted = true,
                settings = AppSettings(onboardingCompleted = true),
                isStorageDegraded = true,
            )
        assertTrue(state.observationFailed)
    }

    private fun mapToBannerStateWithAppSearch(
        statusMap: Map<TrackingKey, TrackingStatus>,
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
