package host.serv.djbigmuh.minutewatcher.core.common.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class PermissionManagerNotificationApiTest {
    private val context: Context = mock()
    private lateinit var permissionManager: PermissionManager

    @Before
    fun setup() {
        initTestLogger(context)
        whenever(context.packageName).thenReturn("host.serv.djbigmuh.minutewatcher")
        permissionManager = PermissionManager(context, null)
    }

    @After
    fun tearDown() {
        AppLogger.resetForTest()
    }

    @Test
    fun `hasNotificationPermission returns true when granted on API 33+`() {
        mockStatic(ContextCompat::class.java).use { mockedContextCompat ->
            mockedContextCompat
                .`when`<Int> {
                    ContextCompat.checkSelfPermission(any(), eq(Manifest.permission.POST_NOTIFICATIONS))
                }.thenReturn(PackageManager.PERMISSION_GRANTED)

            assertTrue(permissionManager.hasNotificationPermission())
        }
    }

    @Test
    fun `hasNotificationPermission returns false when denied on API 33+`() {
        mockStatic(ContextCompat::class.java).use { mockedContextCompat ->
            mockedContextCompat
                .`when`<Int> {
                    ContextCompat.checkSelfPermission(any(), eq(Manifest.permission.POST_NOTIFICATIONS))
                }.thenReturn(PackageManager.PERMISSION_DENIED)

            assertFalse(permissionManager.hasNotificationPermission())
        }
    }

    @Test
    fun `repeated calls to hasNotificationPermission with unchanged result yield single log entry`() {
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
                    ContextCompat.checkSelfPermission(any(), eq(Manifest.permission.POST_NOTIFICATIONS))
                }.thenAnswer { currentPermissionResult }

            repeat(5) {
                permissionManager.hasNotificationPermission()
            }

            assertEquals(1, capturedLogs.size)
            assertEquals("Notification permission state: Granted", capturedLogs.first())

            currentPermissionResult = PackageManager.PERMISSION_DENIED
            repeat(5) {
                permissionManager.hasNotificationPermission()
            }

            assertEquals(2, capturedLogs.size)
            assertEquals("Notification permission state: Denied", capturedLogs.last())
        }
    }
}
