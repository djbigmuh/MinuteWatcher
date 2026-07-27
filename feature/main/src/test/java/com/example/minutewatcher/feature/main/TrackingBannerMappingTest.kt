package com.example.minutewatcher.feature.main

import com.example.minutewatcher.core.model.TrackingStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingBannerMappingTest {
    @Test
    fun `test phone permission error maps to phone banner`() {
        val statusMap =
            mapOf(
                "calls" to TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING),
            )

        val state = mapToBannerState(statusMap)

        assertTrue(state.showPhonePermissionBanner)
        assertFalse(state.showUsageAccessBanner)
    }

    @Test
    fun `test usage access error maps to usage banner`() {
        val statusMap =
            mapOf(
                "traffic" to TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING),
            )

        val state = mapToBannerState(statusMap)

        assertTrue(state.showUsageAccessBanner)
        assertFalse(state.showPhonePermissionBanner)
    }

    @Test
    fun `test database error maps to appsearch banner`() {
        val statusMap =
            mapOf(
                "appsearch" to TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR),
            )

        val state = mapToBannerState(statusMap)

        assertTrue(state.showAppSearchErrorBanner)
    }

    @Test
    fun `test permanently denied phone permission`() {
        val statusMap = mapOf<String, TrackingStatus>()

        val state = mapToBannerState(statusMap, isPhonePermanentlyDenied = true)

        assertTrue(state.isPhonePermissionPermanentlyDenied)
    }

    private fun mapToBannerState(
        statusMap: Map<String, TrackingStatus>,
        isPhonePermanentlyDenied: Boolean = false,
    ): TrackingBannerState {
        val calls = statusMap["calls"]
        val traffic = statusMap["traffic"]
        val appsearch = statusMap["appsearch"]

        return TrackingBannerState(
            showPhonePermissionBanner =
                calls is TrackingStatus.Error &&
                    calls.type == TrackingStatus.ErrorType.PERMISSION_MISSING,
            isPhonePermissionPermanentlyDenied = isPhonePermanentlyDenied,
            showUsageAccessBanner =
                traffic is TrackingStatus.Error &&
                    traffic.type == TrackingStatus.ErrorType.PERMISSION_MISSING,
            showTrafficErrorBanner =
                traffic is TrackingStatus.Error &&
                    traffic.type != TrackingStatus.ErrorType.PERMISSION_MISSING,
            showAppSearchErrorBanner = appsearch is TrackingStatus.Error,
        )
    }
}
