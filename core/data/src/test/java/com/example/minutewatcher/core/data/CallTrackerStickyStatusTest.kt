package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CallTrackerStickyStatusTest : CallTrackerTestFixtures() {
    @Test
    fun `transient error UNKNOWN is cleared by successful non-forced refresh`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val tracker = createTracker(backgroundScope)

            // 1. Set transient error
            tracker.updateStatus(TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, "test"), force = true)
            assertEquals(TrackingStatus.ErrorType.UNKNOWN, (tracker.status.value as TrackingStatus.Error).type)

            // 2. Perform non-forced refresh with success
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(emptyList())
            tracker.refreshStatus(force = false)

            // 3. Status should be Idle (transient error cleared)
            assertEquals(TrackingStatus.Idle, tracker.status.value)
        }

    @Test
    fun `transient error INACCURATE_ATTRIBUTION is cleared by successful non-forced refresh`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val tracker = createTracker(backgroundScope)

            // 1. Set transient error
            tracker.updateStatus(TrackingStatus.Error(TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION), force = true)
            assertEquals(
                TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION,
                (tracker.status.value as TrackingStatus.Error).type,
            )

            // 2. Perform non-forced refresh with success
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(emptyList())
            tracker.refreshStatus(force = false)

            // 3. Status should be Idle
            assertEquals(TrackingStatus.Idle, tracker.status.value)
        }

    @Test
    fun `sticky error DATABASE_ERROR is NOT cleared by non-forced refresh`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val tracker = createTracker(backgroundScope)

            // 1. Set sticky error
            tracker.updateStatus(TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR), force = true)

            // 2. Perform non-forced refresh
            tracker.refreshStatus(force = false)

            // 3. Status remains sticky error
            assertEquals(TrackingStatus.ErrorType.DATABASE_ERROR, (tracker.status.value as TrackingStatus.Error).type)

            // 4. Force refresh clears it
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(emptyList())
            tracker.refreshStatus(force = true)
            assertEquals(TrackingStatus.Idle, tracker.status.value)
        }

    @Test
    fun `sticky error PERMISSION_MISSING is NOT cleared until permissions regained`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val tracker = createTracker(backgroundScope)

            // 1. Missing permission
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(false)
            tracker.refreshStatus(force = true)
            assertEquals(
                TrackingStatus.ErrorType.PERMISSION_MISSING,
                (tracker.status.value as TrackingStatus.Error).type,
            )

            // 2. Refresh without gaining permission
            tracker.refreshStatus(force = true)
            assertEquals(
                TrackingStatus.ErrorType.PERMISSION_MISSING,
                (tracker.status.value as TrackingStatus.Error).type,
            )

            // 3. Regain permission and refresh
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(emptyList())
            tracker.refreshStatus(force = false) // Success should clear it because it re-evaluates checkPermissions
            assertEquals(TrackingStatus.Idle, tracker.status.value)
        }
}
