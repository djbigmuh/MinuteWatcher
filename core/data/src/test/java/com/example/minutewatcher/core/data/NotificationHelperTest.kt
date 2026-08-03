package com.example.minutewatcher.core.data

import android.app.NotificationManager
import android.content.Context
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.domain.NavigationProvider
import com.example.minutewatcher.core.resources.R
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationHelperTest {

    private val context: Context = mock()
    private val navigationProvider: NavigationProvider = mock()
    private val permissionManager: PermissionManager = mock()
    private val notificationManager: NotificationManager = mock()

    private lateinit var helper: NotificationHelper

    @Before
    fun setup() {
        whenever(context.getSystemService(Context.NOTIFICATION_SERVICE)).thenReturn(notificationManager)
        whenever(context.getString(R.string.notification_channel_name)).thenReturn("Usage Monitoring")
        whenever(context.packageName).thenReturn("com.example.minutewatcher")
        whenever(navigationProvider.mainActivityClassName).thenReturn("MainActivity")
        whenever(notificationManager.areNotificationsEnabled()).thenReturn(true)

        helper = NotificationHelper(context, navigationProvider, permissionManager)
    }

    @Test
    fun `sendUsageNotification does nothing if permission is missing`() {
        whenever(permissionManager.hasNotificationPermission()).thenReturn(false)

        helper.sendUsageNotification("Title", "Message", 0, 1)

        verify(notificationManager, never()).notify(anyInt(), any())
    }

    @Test
    fun `sendUsageNotification notifies if permission is granted`() {
        whenever(permissionManager.hasNotificationPermission()).thenReturn(true)

        helper.sendUsageNotification("Title", "Message", 0, 1)

        verify(notificationManager).notify(anyInt(), any())
    }
}
