package com.example.minutewatcher.core.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.domain.NavigationProvider
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.resources.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

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
    fun `aod summary channel is created with min importance`() {
        val channel = notificationManager.getNotificationChannel(Constants.Notifications.AOD_SUMMARY_CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_MIN, channel.importance)
        assertEquals(
            context.getString(R.string.notification_channel_aod_summary_name),
            channel.name,
        )
    }

    @Test
    fun `sendUsageNotification does nothing if permission is missing`() {
        whenever(permissionManager.hasNotificationPermission()).thenReturn(false)

        helper.sendUsageNotification("Title", "Message", 0, 1)

        val shadowNotificationManager = shadowOf(notificationManager)
        assertEquals(0, shadowNotificationManager.allNotifications.size)
    }

    @Test
    fun `sendUsageNotification notifies if permission is granted`() {
        whenever(permissionManager.hasNotificationPermission()).thenReturn(true)

        helper.sendUsageNotification("Title", "Message", 0, 1)

        val shadowNotificationManager = shadowOf(notificationManager)
        assertEquals(1, shadowNotificationManager.allNotifications.size)
        val notification = shadowNotificationManager.allNotifications[0]
        assertEquals("Title", notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
        assertEquals("Message", notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun `sendAodSummaryNotification posts notification when permission granted`() {
        whenever(permissionManager.hasNotificationPermission()).thenReturn(true)

        val success = helper.sendAodSummaryNotification("AOD Title", "AOD Line", "Expanded text")

        assertTrue(success)
        val shadowNotificationManager = shadowOf(notificationManager)
        assertEquals(1, shadowNotificationManager.allNotifications.size)
        val notification = shadowNotificationManager.allNotifications[0]
        val title = notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString()
        val text = notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        val bigText = notification.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT).toString()
        assertEquals("AOD Title", title)
        assertEquals("AOD Line", text)
        assertEquals("Expanded text", bigText)
        assertTrue((notification.flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0)
    }

    @Test
    fun `sendAodSummaryNotification returns false when permission missing`() {
        whenever(permissionManager.hasNotificationPermission()).thenReturn(false)

        val success = helper.sendAodSummaryNotification("Title", "Line", "Expanded")

        assertFalse(success)
        val shadowNotificationManager = shadowOf(notificationManager)
        assertEquals(0, shadowNotificationManager.allNotifications.size)
    }

    @Test
    fun `sendAodSummaryNotification returns false when channel is blocked`() {
        whenever(permissionManager.hasNotificationPermission()).thenReturn(true)

        // Block the channel
        val blockedChannel =
            NotificationChannel(
                Constants.Notifications.AOD_SUMMARY_CHANNEL_ID,
                "AOD Channel",
                NotificationManager.IMPORTANCE_NONE,
            )
        notificationManager.createNotificationChannel(blockedChannel)

        val success = helper.sendAodSummaryNotification("Title", "Line", "Expanded")

        assertFalse(success)
        val shadowNotificationManager = shadowOf(notificationManager)
        assertEquals(0, shadowNotificationManager.allNotifications.size)
    }

    @Test
    fun `cancelAodSummaryNotification removes notification`() {
        whenever(permissionManager.hasNotificationPermission()).thenReturn(true)
        helper.sendAodSummaryNotification("Title", "Line", "Expanded")

        val shadowNotificationManager = shadowOf(notificationManager)
        assertEquals(1, shadowNotificationManager.allNotifications.size)

        helper.cancelAodSummaryNotification()

        assertEquals(0, shadowNotificationManager.allNotifications.size)
    }

    @Test
    fun `isAodSummaryAvailable returns true in base scenario`() {
        whenever(permissionManager.hasNotificationPermission()).thenReturn(true)

        assertTrue(helper.isAodSummaryAvailable())
    }

    @Test
    fun `isAodSummaryAvailable returns false when permission missing`() {
        whenever(permissionManager.hasNotificationPermission()).thenReturn(false)

        assertFalse(helper.isAodSummaryAvailable())
    }

    @Test
    fun `isAodSummaryAvailable returns false when channel is blocked`() {
        whenever(permissionManager.hasNotificationPermission()).thenReturn(true)

        val blockedChannel =
            NotificationChannel(
                Constants.Notifications.AOD_SUMMARY_CHANNEL_ID,
                "AOD Channel",
                NotificationManager.IMPORTANCE_NONE,
            )
        notificationManager.createNotificationChannel(blockedChannel)

        assertFalse(helper.isAodSummaryAvailable())
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

        val shadowNotificationManager = shadowOf(notificationManager)
        assertEquals(0, shadowNotificationManager.allNotifications.size)
    }

    @Test
    fun `aod summary methods handle null NotificationManager gracefully`() {
        val nullServiceContext =
            object : android.content.ContextWrapper(context) {
                override fun getSystemService(name: String): Any? = null

                override fun getSystemServiceName(serviceClass: Class<*>): String? = null
            }

        whenever(permissionManager.hasNotificationPermission()).thenReturn(true)

        val nullServiceHelper = NotificationHelper(nullServiceContext, navigationProvider, permissionManager)

        assertFalse(nullServiceHelper.sendAodSummaryNotification("Title", "Line", "Text"))
        nullServiceHelper.cancelAodSummaryNotification()
        assertFalse(nullServiceHelper.isAodSummaryAvailable())
    }
}
