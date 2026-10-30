package host.serv.djbigmuh.minutewatcher.core.data

import android.os.Build
import android.telephony.TelephonyManager
import host.serv.djbigmuh.minutewatcher.core.common.util.PermissionManager
import host.serv.djbigmuh.minutewatcher.core.domain.CallSessionRepository
import host.serv.djbigmuh.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class CallEndResolverTest {
    private val callSessionRepository: CallSessionRepository = mock()
    private val permissionManager: PermissionManager = mock()
    private val telephonyManager: TelephonyManager = mock()

    private lateinit var resolver: CallEndResolver

    @Before
    fun setup() {
        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
        resolver = CallEndResolver(callSessionRepository, permissionManager, telephonyManager)
    }

    @Test
    fun `resolveUnknownSubIdEnd returns PERMISSION_MISSING error when permission is missing`() =
        runTest {
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(false)

            val result = resolver.resolveUnknownSubIdEnd()

            assertTrue(result is CallEndResolver.ResolveResult.Error)
            val error = (result as CallEndResolver.ResolveResult.Error).error
            assertEquals(TrackingStatus.ErrorType.PERMISSION_MISSING, error.type)
        }

    @Test
    fun `resolveUnknownSubIdEnd returns UNKNOWN error when fetchActiveSubIds returns null`() =
        runTest {
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(null)

            val result = resolver.resolveUnknownSubIdEnd()

            assertTrue(result is CallEndResolver.ResolveResult.Error)
            val error = (result as CallEndResolver.ResolveResult.Error).error
            assertEquals(TrackingStatus.ErrorType.UNKNOWN, error.type)
        }

    @Test
    fun `resolveUnknownSubIdEnd returns empty SubIds when active sessions list is empty`() =
        runTest {
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(emptyList())

            val result = resolver.resolveUnknownSubIdEnd()

            assertTrue(result is CallEndResolver.ResolveResult.SubIds)
            assertEquals(emptyList<Int>(), (result as CallEndResolver.ResolveResult.SubIds).ids)
        }

    @Test
    fun `resolveUnknownSubIdEnd returns all active SubIds when telephonyManager is null`() =
        runTest {
            val nullTelephonyResolver = CallEndResolver(callSessionRepository, permissionManager, null)
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(1, 2))

            val result = nullTelephonyResolver.resolveUnknownSubIdEnd()

            assertTrue(result is CallEndResolver.ResolveResult.SubIds)
            assertEquals(listOf(1, 2), (result as CallEndResolver.ResolveResult.SubIds).ids)
        }

    @Test
    @Config(sdk = [Build.VERSION_CODES.S]) // behavior-specific SDK
    fun `resolveUnknownSubIdEnd returns all active SubIds when all subscriptions are IDLE on SDK 31+`() =
        runTest {
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(1, 2))
            val sub1Manager: TelephonyManager = mock()
            val sub2Manager: TelephonyManager = mock()
            whenever(telephonyManager.createForSubscriptionId(1)).thenReturn(sub1Manager)
            whenever(telephonyManager.createForSubscriptionId(2)).thenReturn(sub2Manager)
            whenever(sub1Manager.callStateForSubscription).thenReturn(TelephonyManager.CALL_STATE_IDLE)
            whenever(sub2Manager.callStateForSubscription).thenReturn(TelephonyManager.CALL_STATE_IDLE)

            val result = resolver.resolveUnknownSubIdEnd()

            assertTrue(result is CallEndResolver.ResolveResult.SubIds)
            assertEquals(listOf(1, 2), (result as CallEndResolver.ResolveResult.SubIds).ids)
        }

    @Test
    @Config(sdk = [Build.VERSION_CODES.S]) // behavior-specific SDK
    fun `resolveUnknownSubIdEnd returns single SubId when single active session is OFFHOOK on SDK 31+`() =
        runTest {
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(1))
            val sub1Manager: TelephonyManager = mock()
            whenever(telephonyManager.createForSubscriptionId(1)).thenReturn(sub1Manager)
            whenever(sub1Manager.callStateForSubscription).thenReturn(TelephonyManager.CALL_STATE_OFFHOOK)

            val result = resolver.resolveUnknownSubIdEnd()

            assertTrue(result is CallEndResolver.ResolveResult.SubIds)
            assertEquals(listOf(1), (result as CallEndResolver.ResolveResult.SubIds).ids)
        }

    @Test
    @Config(sdk = [Build.VERSION_CODES.S]) // behavior-specific SDK
    fun `resolveUnknownSubIdEnd returns single SubId when query throws RuntimeException on SDK 31+`() =
        runTest {
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(1))
            val sub1Manager: TelephonyManager = mock()
            whenever(telephonyManager.createForSubscriptionId(1)).thenReturn(sub1Manager)
            whenever(sub1Manager.callStateForSubscription).thenThrow(RuntimeException("Telephony error"))

            val result = resolver.resolveUnknownSubIdEnd()

            assertTrue(result is CallEndResolver.ResolveResult.SubIds)
            assertEquals(listOf(1), (result as CallEndResolver.ResolveResult.SubIds).ids)
        }

    @Test
    @Config(sdk = [Build.VERSION_CODES.S]) // behavior-specific SDK
    fun `resolveUnknownSubIdEnd returns single IDLE SubId when one of multiple is IDLE on SDK 31+`() =
        runTest {
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(1, 2))
            val sub1Manager: TelephonyManager = mock()
            val sub2Manager: TelephonyManager = mock()
            whenever(telephonyManager.createForSubscriptionId(1)).thenReturn(sub1Manager)
            whenever(telephonyManager.createForSubscriptionId(2)).thenReturn(sub2Manager)
            whenever(sub1Manager.callStateForSubscription).thenReturn(TelephonyManager.CALL_STATE_OFFHOOK)
            whenever(sub2Manager.callStateForSubscription).thenReturn(TelephonyManager.CALL_STATE_IDLE)

            val result = resolver.resolveUnknownSubIdEnd()

            assertTrue(result is CallEndResolver.ResolveResult.SubIds)
            assertEquals(listOf(2), (result as CallEndResolver.ResolveResult.SubIds).ids)
        }

    @Test
    @Config(sdk = [Build.VERSION_CODES.S]) // behavior-specific SDK
    fun `resolveUnknownSubIdEnd returns INACCURATE_ATTRIBUTION when none are IDLE on SDK 31+`() =
        runTest {
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(1, 2))
            val sub1Manager: TelephonyManager = mock()
            val sub2Manager: TelephonyManager = mock()
            whenever(telephonyManager.createForSubscriptionId(1)).thenReturn(sub1Manager)
            whenever(telephonyManager.createForSubscriptionId(2)).thenReturn(sub2Manager)
            whenever(sub1Manager.callStateForSubscription).thenReturn(TelephonyManager.CALL_STATE_OFFHOOK)
            whenever(sub2Manager.callStateForSubscription).thenReturn(TelephonyManager.CALL_STATE_OFFHOOK)

            val result = resolver.resolveUnknownSubIdEnd()

            assertTrue(result is CallEndResolver.ResolveResult.Error)
            val error = (result as CallEndResolver.ResolveResult.Error).error
            assertEquals(TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION, error.type)
        }

    @Test
    @Config(sdk = [Build.VERSION_CODES.S]) // behavior-specific SDK
    fun `resolveUnknownSubIdEnd returns INACCURATE_ATTRIBUTION when two of 3 are IDLE on SDK 31+`() =
        runTest {
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(1, 2, 3))
            val sub1Manager: TelephonyManager = mock()
            val sub2Manager: TelephonyManager = mock()
            val sub3Manager: TelephonyManager = mock()
            whenever(telephonyManager.createForSubscriptionId(1)).thenReturn(sub1Manager)
            whenever(telephonyManager.createForSubscriptionId(2)).thenReturn(sub2Manager)
            whenever(telephonyManager.createForSubscriptionId(3)).thenReturn(sub3Manager)
            whenever(sub1Manager.callStateForSubscription).thenReturn(TelephonyManager.CALL_STATE_IDLE)
            whenever(sub2Manager.callStateForSubscription).thenReturn(TelephonyManager.CALL_STATE_IDLE)
            whenever(sub3Manager.callStateForSubscription).thenReturn(TelephonyManager.CALL_STATE_OFFHOOK)

            val result = resolver.resolveUnknownSubIdEnd()

            assertTrue(result is CallEndResolver.ResolveResult.Error)
            val error = (result as CallEndResolver.ResolveResult.Error).error
            assertEquals(TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION, error.type)
        }

    @Test
    @Config(sdk = [Build.VERSION_CODES.R]) // behavior-specific SDK
    fun `resolveUnknownSubIdEnd returns all active SubIds when global callState is IDLE on SDK below 31`() =
        runTest {
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(1, 2))
            @Suppress("DEPRECATION") // Legacy test API
            whenever(telephonyManager.callState).thenReturn(TelephonyManager.CALL_STATE_IDLE)

            val result = resolver.resolveUnknownSubIdEnd()

            assertTrue(result is CallEndResolver.ResolveResult.SubIds)
            assertEquals(listOf(1, 2), (result as CallEndResolver.ResolveResult.SubIds).ids)
        }

    @Test
    @Config(sdk = [Build.VERSION_CODES.R]) // behavior-specific SDK
    fun `resolveUnknownSubIdEnd returns single SubId when global callState not IDLE on SDK below 31`() =
        runTest {
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(1))
            @Suppress("DEPRECATION") // Suppressing deprecation warning for legacy telephony call state API in tests
            whenever(telephonyManager.callState).thenReturn(TelephonyManager.CALL_STATE_OFFHOOK)

            val result = resolver.resolveUnknownSubIdEnd()

            assertTrue(result is CallEndResolver.ResolveResult.SubIds)
            assertEquals(listOf(1), (result as CallEndResolver.ResolveResult.SubIds).ids)
        }

    @Test
    @Config(sdk = [Build.VERSION_CODES.R]) // behavior-specific SDK
    fun `resolveUnknownSubIdEnd returns INACCURATE_ATTRIBUTION when global callState not IDLE on SDK below 31`() =
        runTest {
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(1, 2))
            @Suppress("DEPRECATION") // Suppressing deprecation warning for legacy telephony call state API in tests
            whenever(telephonyManager.callState).thenReturn(TelephonyManager.CALL_STATE_OFFHOOK)

            val result = resolver.resolveUnknownSubIdEnd()

            assertTrue(result is CallEndResolver.ResolveResult.Error)
            val error = (result as CallEndResolver.ResolveResult.Error).error
            assertEquals(TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION, error.type)
        }

    @Test(expected = SecurityException::class)
    fun `resolveUnknownSubIdEnd rethrows SecurityException during fetchActiveSubIds`() =
        runTest {
            whenever(callSessionRepository.getActiveCallSubIds()).thenThrow(SecurityException("Permission revoked"))

            resolver.resolveUnknownSubIdEnd()
        }
}
