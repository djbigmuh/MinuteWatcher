package com.example.minutewatcher.core.common.util

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class PermissionManagerTest {
    private val context: Context = mock()
    private val subscriptionManager: SubscriptionManager = mock()
    private lateinit var permissionManager: PermissionManager

    @Before
    fun setup() {
        initTestLogger(context)
        whenever(context.packageName).thenReturn("com.example.minutewatcher")
        whenever(context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)).thenReturn(subscriptionManager)
        permissionManager = PermissionManager(context, subscriptionManager)
    }

    @After
    fun tearDown() {
        AppLogger.resetForTest()
    }

    @Test
    fun `isTelephonySupported returns true when feature present`() {
        val pm: PackageManager = mock()
        whenever(context.packageManager).thenReturn(pm)
        whenever(pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)).thenReturn(true)

        assertTrue(permissionManager.isTelephonySupported())
    }

    @Test
    fun `isTelephonySupported returns false when feature missing`() {
        val pm: PackageManager = mock()
        whenever(context.packageManager).thenReturn(pm)
        whenever(pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)).thenReturn(false)

        assertFalse(permissionManager.isTelephonySupported())
    }

    @Test
    fun `hasPhoneStatePermission returns true when granted`() {
        mockStatic(ContextCompat::class.java).use { mockedContextCompat ->
            mockedContextCompat
                .`when`<Int> {
                    ContextCompat.checkSelfPermission(any(), eq(Manifest.permission.READ_PHONE_STATE))
                }.thenReturn(PackageManager.PERMISSION_GRANTED)

            assertTrue(permissionManager.hasPhoneStatePermission())
        }
    }

    @Test
    fun `hasPhoneStatePermission returns false when denied`() {
        mockStatic(ContextCompat::class.java).use { mockedContextCompat ->
            mockedContextCompat
                .`when`<Int> {
                    ContextCompat.checkSelfPermission(any(), eq(Manifest.permission.READ_PHONE_STATE))
                }.thenReturn(PackageManager.PERMISSION_DENIED)

            assertFalse(permissionManager.hasPhoneStatePermission())
        }
    }

    @Test
    fun `getActiveSubscriptions returns empty list when permission is missing`() {
        mockStatic(ContextCompat::class.java).use { mockedContextCompat ->
            mockedContextCompat
                .`when`<Int> {
                    ContextCompat.checkSelfPermission(any(), eq(Manifest.permission.READ_PHONE_STATE))
                }.thenReturn(PackageManager.PERMISSION_DENIED)

            assertTrue(permissionManager.getActiveSubscriptions().isEmpty())
        }
    }

    @Test
    fun `getDefaultVoiceSubscriptionId returns negative one when permission is missing`() {
        mockStatic(ContextCompat::class.java).use { mockedContextCompat ->
            mockedContextCompat
                .`when`<Int> {
                    ContextCompat.checkSelfPermission(any(), eq(Manifest.permission.READ_PHONE_STATE))
                }.thenReturn(PackageManager.PERMISSION_DENIED)

            assertEquals(-1, permissionManager.getDefaultVoiceSubscriptionId())
        }
    }

    @Test
    fun `getDefaultDataSubscriptionId returns negative one when permission is missing`() {
        mockStatic(ContextCompat::class.java).use { mockedContextCompat ->
            mockedContextCompat
                .`when`<Int> {
                    ContextCompat.checkSelfPermission(any(), eq(Manifest.permission.READ_PHONE_STATE))
                }.thenReturn(PackageManager.PERMISSION_DENIED)

            assertEquals(-1, permissionManager.getDefaultDataSubscriptionId())
        }
    }

    @Test
    fun `hasUsageStatsPermission returns true when allowed`() {
        val appOps: AppOpsManager = mock()
        whenever(context.getSystemService(Context.APP_OPS_SERVICE)).thenReturn(appOps)
        whenever(appOps.checkOpNoThrow(eq(AppOpsManager.OPSTR_GET_USAGE_STATS), any(), any()))
            .thenReturn(AppOpsManager.MODE_ALLOWED)

        assertTrue(permissionManager.hasUsageStatsPermission())
    }

    @Test
    fun `hasUsageStatsPermission returns false when ignored`() {
        val appOps: AppOpsManager = mock()
        whenever(context.getSystemService(Context.APP_OPS_SERVICE)).thenReturn(appOps)
        whenever(appOps.checkOpNoThrow(eq(AppOpsManager.OPSTR_GET_USAGE_STATS), any(), any()))
            .thenReturn(AppOpsManager.MODE_IGNORED)

        assertFalse(permissionManager.hasUsageStatsPermission())
    }

    @Test
    fun `hasUsageStatsPermission returns false when exception occurs`() {
        val appOps: AppOpsManager = mock()
        whenever(context.getSystemService(Context.APP_OPS_SERVICE)).thenReturn(appOps)
        whenever(appOps.checkOpNoThrow(eq(AppOpsManager.OPSTR_GET_USAGE_STATS), any(), any()))
            .thenThrow(RuntimeException("Test error"))

        assertFalse(permissionManager.hasUsageStatsPermission())
    }

    @Test
    fun `repeated calls to hasPhoneStatePermission with unchanged result yield single log entry`() {
        val capturedLogs = mutableListOf<String>()
        val capturingLogger =
            object : Logger {
                override fun d(
                    tag: String,
                    message: String,
                ) {
                    capturedLogs.add(message)
                }

                override fun w(
                    tag: String,
                    message: String,
                    throwable: Throwable?,
                ) {
                    capturedLogs.add(message)
                }

                override fun e(
                    tag: String,
                    message: String,
                    throwable: Throwable?,
                ) {
                    capturedLogs.add(message)
                }
            }
        AppLogger.init(context, capturingLogger)

        var currentPermissionResult = PackageManager.PERMISSION_GRANTED
        mockStatic(ContextCompat::class.java).use { mockedContextCompat ->
            mockedContextCompat
                .`when`<Int> {
                    ContextCompat.checkSelfPermission(any(), eq(Manifest.permission.READ_PHONE_STATE))
                }.thenAnswer { currentPermissionResult }

            repeat(5) {
                permissionManager.hasPhoneStatePermission()
            }

            assertEquals(1, capturedLogs.size)
            assertEquals("Phone state permission state: Granted", capturedLogs.first())

            currentPermissionResult = PackageManager.PERMISSION_DENIED
            repeat(5) {
                permissionManager.hasPhoneStatePermission()
            }

            assertEquals(2, capturedLogs.size)
            assertEquals("Phone state permission state: Denied", capturedLogs.last())
        }
    }

    @Test
    fun `repeated calls to hasUsageStatsPermission with unchanged result yield single log entry`() {
        val capturedLogs = mutableListOf<String>()
        val capturingLogger =
            object : Logger {
                override fun d(
                    tag: String,
                    message: String,
                ) {
                    capturedLogs.add(message)
                }

                override fun w(
                    tag: String,
                    message: String,
                    throwable: Throwable?,
                ) {
                    capturedLogs.add(message)
                }

                override fun e(
                    tag: String,
                    message: String,
                    throwable: Throwable?,
                ) {
                    capturedLogs.add(message)
                }
            }
        AppLogger.init(context, capturingLogger)

        val appOps: AppOpsManager = mock()
        whenever(context.getSystemService(Context.APP_OPS_SERVICE)).thenReturn(appOps)

        var currentOpMode = AppOpsManager.MODE_ALLOWED
        whenever(appOps.checkOpNoThrow(eq(AppOpsManager.OPSTR_GET_USAGE_STATS), any(), any()))
            .thenAnswer { currentOpMode }

        repeat(5) {
            permissionManager.hasUsageStatsPermission()
        }

        assertEquals(1, capturedLogs.size)
        assertEquals("Usage stats permission state: Granted", capturedLogs.first())

        currentOpMode = AppOpsManager.MODE_IGNORED
        repeat(5) {
            permissionManager.hasUsageStatsPermission()
        }

        assertEquals(2, capturedLogs.size)
        assertEquals("Usage stats permission state: Denied", capturedLogs.last())
    }
}
