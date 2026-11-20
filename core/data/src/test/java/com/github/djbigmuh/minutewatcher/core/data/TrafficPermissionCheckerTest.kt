package com.github.djbigmuh.minutewatcher.core.data

import com.github.djbigmuh.minutewatcher.core.common.util.PermissionManager
import com.github.djbigmuh.minutewatcher.core.model.TrackingStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class TrafficPermissionCheckerTest {
    private val permissionManager: PermissionManager = mock()
    private lateinit var checker: TrafficPermissionChecker

    @Before
    fun setup() {
        checker = TrafficPermissionChecker(permissionManager)
        // Default: everything supported and granted
        whenever(permissionManager.isTelephonySupported()).thenReturn(true)
        whenever(permissionManager.hasUsageStatsPermission()).thenReturn(true)
        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
    }

    @Test
    fun `checkPermissions returns true when everything is okay`() {
        val onError: (TrackingStatus) -> Unit = mock()
        val result = checker.checkPermissions(onError)

        assertTrue(result)
        verify(onError, never()).invoke(any())
    }

    @Test
    fun `checkPermissions returns false and NOT_SUPPORTED when telephony not supported`() {
        whenever(permissionManager.isTelephonySupported()).thenReturn(false)
        val onError: (TrackingStatus) -> Unit = mock()

        val result = checker.checkPermissions(onError)

        assertFalse(result)
        verify(onError).invoke(
            argThat {
                this is TrackingStatus.Error && type == TrackingStatus.ErrorType.NOT_SUPPORTED
            },
        )
    }

    @Test
    fun `checkPermissions returns false and PERMISSION_MISSING when usage stats missing`() {
        whenever(permissionManager.hasUsageStatsPermission()).thenReturn(false)
        val onError: (TrackingStatus) -> Unit = mock()

        val result = checker.checkPermissions(onError)

        assertFalse(result)
        verify(onError).invoke(
            argThat {
                this is TrackingStatus.Error && type == TrackingStatus.ErrorType.PERMISSION_MISSING
            },
        )
    }

    // Helper to use argThat with TrackingStatus
    private fun <T> argThat(predicate: T.() -> Boolean): T = org.mockito.kotlin.argThat { predicate(this) }
}
