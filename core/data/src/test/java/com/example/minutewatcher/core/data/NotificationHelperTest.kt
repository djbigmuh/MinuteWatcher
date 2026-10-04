package com.example.minutewatcher.core.data

import android.app.NotificationManager
import android.content.Context
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.domain.NavigationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationHelperTest {
    private lateinit var context: Context
    private val navigationProvider: NavigationProvider = mock()
    private val permissionManager: PermissionManager = mock()
    private lateinit var notificationManager: NotificationManager

    private lateinit var helper: NotificationHelper

    @Before
    fun setup() {
        context =
            androidx.test.core.app.ApplicationProvider
                .getApplicationContext()
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        whenever(navigationProvider.mainActivityClassName).thenReturn("MainActivity")

        helper = NotificationHelper(context, navigationProvider, permissionManager)
    }

    @Test
    fun `sendUsageNotification does nothing if permission is missing`() {
        whenever(permissionManager.hasNotificationPermission()).thenReturn(false)

        helper.sendUsageNotification("Title", "Message", 0, 1)

        val shadowNotificationManager = org.robolectric.Shadows.shadowOf(notificationManager)
        assertEquals(0, shadowNotificationManager.allNotifications.size)
    }

    @Test
    fun `sendUsageNotification notifies if permission is granted`() {
        whenever(permissionManager.hasNotificationPermission()).thenReturn(true)

        helper.sendUsageNotification("Title", "Message", 0, 1)

        val shadowNotificationManager = org.robolectric.Shadows.shadowOf(notificationManager)
        assertEquals(1, shadowNotificationManager.allNotifications.size)
        val notification = shadowNotificationManager.allNotifications[0]
        assertEquals("Title", notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
        assertEquals("Message", notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun `sendUsageNotification handles null NotificationManager gracefully without throwing`() {
        val nullServiceContext =
            object : android.content.ContextWrapper(context) {
                override fun getSystemService(name: String): Any? = null

                override fun getSystemServiceName(serviceClass: Class<*>): String? = null
            }

        whenever(permissionManager.hasNotificationPermission()).thenReturn(true)

        val nullServiceHelper = NotificationHelper(nullServiceContext, navigationProvider, permissionManager)

        nullServiceHelper.sendUsageNotification("Title", "Message", 0, 1)
        nullServiceHelper.sendUsageNotification("Title", "Message", 0, 1)

        val shadowNotificationManager = org.robolectric.Shadows.shadowOf(notificationManager)
        assertEquals(0, shadowNotificationManager.allNotifications.size)
    }
}
