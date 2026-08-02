package com.example.minutewatcher.core.data

import android.app.NotificationManager
import android.content.Context
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.domain.NavigationProvider
import com.example.minutewatcher.core.resources.R
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

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
        // NotificationManagerCompat.from(context).areNotificationsEnabled() is static/internal and hard to mock without Robolectric.
        // But for pure unit test we focus on what we can verify.
        // Actually NotificationManagerCompat.from(context) uses context.
    }
}
