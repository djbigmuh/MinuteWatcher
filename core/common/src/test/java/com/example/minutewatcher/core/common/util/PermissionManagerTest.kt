package com.example.minutewatcher.core.common.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
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

    private val mockLogger =
        object : Logger {
            override fun d(
                tag: String,
                message: String,
            ) {
                // No-op for testing
            }

            override fun w(
                tag: String,
                message: String,
                throwable: Throwable?,
            ) {
                // No-op for testing
            }

            override fun e(
                tag: String,
                message: String,
                throwable: Throwable?,
            ) {
                // No-op for testing
            }
        }

    @Before
    fun setup() {
        whenever(context.filesDir).thenReturn(mock())
        AppLogger.init(context, mockLogger)
        whenever(context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)).thenReturn(subscriptionManager)
        permissionManager = PermissionManager(context)
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
}
