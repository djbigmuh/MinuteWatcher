package com.example.minutewatcher.core.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
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
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
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
        notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        whenever(navigationProvider.mainActivityClassName).thenReturn("MainActivity")

        helper = NotificationHelper(context, navigationProvider, permissionManager)
    }

    @Test
    fun `aod summary channel is created with low importance and quiet attributes`() {
        val channel =
            notificationManager.getNotificationChannel(
                Constants.Notifications.AOD_SUMMARY_CHANNEL_ID_V2,
            )
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertEquals(
            context.getString(R.string.notification_channel_aod_summary_name),
            channel.name,
        )
        org.junit.Assert.assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        assertFalse(channel.canShowBadge())
    }

    @Test
    fun `helper initialization migrates old aod summary channel to v2 channel and deletes old channel`() {
        val oldChannel =
            NotificationChannel(
                Constants.Notifications.AOD_SUMMARY_CHANNEL_ID,
                "Old AOD",
                NotificationManager.IMPORTANCE_MIN,
            )
        notificationManager.createNotificationChannel(oldChannel)
        assertNotNull(notificationManager.getNotificationChannel(Constants.Notifications.AOD_SUMMARY_CHANNEL_ID))

        val migrationHelper = NotificationHelper(context, navigationProvider, permissionManager)
        assertNotNull(migrationHelper)

        assertNotNull(
            notificationManager.getNotificationChannel(
                Constants.Notifications.AOD_SUMMARY_CHANNEL_ID_V2,
            ),
        )
        org.junit.Assert.assertNull(
            notificationManager.getNotificationChannel(
                Constants.Notifications.AOD_SUMMARY_CHANNEL_ID,
            ),
        )
    }

    @Test
    fun `helper initialization handles missing old channel idempotently`() {
        notificationManager.deleteNotificationChannel(Constants.Notifications.AOD_SUMMARY_CHANNEL_ID)
        notificationManager.deleteNotificationChannel(Constants.Notifications.AOD_SUMMARY_CHANNEL_ID_V2)

        val freshHelper = NotificationHelper(context, navigationProvider, permissionManager)
        assertNotNull(freshHelper)

        assertNotNull(
            notificationManager.getNotificationChannel(
                Constants.Notifications.AOD_SUMMARY_CHANNEL_ID_V2,
            ),
        )
    }

    @Test
    fun `sendUsageNotification does nothing if permission is missing`() {
        whenever(permissionManager.hasNotificationPermission()).thenReturn(false)

        helper.sendUsageNotification("Title", "Message", 0, UsageNotificationType.TRAFFIC)

        val shadowNotificationManager = shadowOf(notificationManager)
        assertEquals(0, shadowNotificationManager.allNotifications.size)
    }

    @Test
    fun `sendUsageNotification notifies if permission is granted`() {
        whenever(permissionManager.hasNotificationPermission()).thenReturn(true)

        helper.sendUsageNotification("Title", "Message", 0, UsageNotificationType.TRAFFIC)

        val shadowNotificationManager = shadowOf(notificationManager)
        // 1 usage notification + 1 group summary notification = 2
        assertEquals(2, shadowNotificationManager.allNotifications.size)
        val usageId =
            NotificationHelper.BASE_NOTIFICATION_ID +
                (0 * NotificationHelper.SLOT_OFFSET) +
                UsageNotificationType.TRAFFIC.idOffset
        val notification = shadowNotificationManager.getNotification(usageId)
        assertNotNull(notification)
        val title =
            notification!!
                .extras
                .getCharSequence(android.app.Notification.EXTRA_TITLE)
                .toString()
        val text =
            notification.extras
                .getCharSequence(android.app.Notification.EXTRA_TEXT)
                .toString()
        assertEquals("Title", title)
        assertEquals("Message", text)
    }

    @Test
    fun `sendUsageNotification publishes group summary notification with required attributes`() {
        whenever(permissionManager.hasNotificationPermission()).thenReturn(true)

        helper.sendUsageNotification("Child Title", "Message", 0, UsageNotificationType.MINUTES)

        val shadowNotificationManager = shadowOf(notificationManager)
        val summaryNotification =
            shadowNotificationManager.getNotification(
                NotificationHelper.GROUP_SUMMARY_NOTIFICATION_ID,
            )
        assertNotNull(summaryNotification)
        assertTrue(NotificationCompat.isGroupSummary(summaryNotification!!))
        assertEquals("usage_monitor_group", summaryNotification.group)
        assertEquals(NotificationCompat.GROUP_ALERT_CHILDREN, summaryNotification.groupAlertBehavior)

        val title =
            summaryNotification.extras
                .getCharSequence(android.app.Notification.EXTRA_TITLE)
                .toString()
        val text =
            summaryNotification.extras
                .getCharSequence(android.app.Notification.EXTRA_TEXT)
                .toString()

        assertEquals(context.getString(R.string.notification_channel_name), title)
        assertEquals(context.getString(R.string.notification_channel_desc), text)
    }

    @Test
    fun `group summary content remains stable across multiple child notifications`() {
        whenever(permissionManager.hasNotificationPermission()).thenReturn(true)

        helper.sendUsageNotification("Slot 0 Minutes", "Msg 1", 0, UsageNotificationType.MINUTES)
        val summary1 = shadowOf(notificationManager).getNotification(NotificationHelper.GROUP_SUMMARY_NOTIFICATION_ID)
        val text1 = summary1!!.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()

        helper.sendUsageNotification("Slot 1 Traffic", "Msg 2", 1, UsageNotificationType.TRAFFIC)
        val summary2 = shadowOf(notificationManager).getNotification(NotificationHelper.GROUP_SUMMARY_NOTIFICATION_ID)
        val text2 = summary2!!.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()

        assertEquals(context.getString(R.string.notification_channel_desc), text1)
        assertEquals(context.getString(R.string.notification_channel_desc), text2)
        assertEquals(text1, text2)
    }

    @Test
    fun `notification IDs and slots are unique without collisions`() {
        val minSlot0 =
            NotificationHelper.BASE_NOTIFICATION_ID +
                (0 * NotificationHelper.SLOT_OFFSET) +
                UsageNotificationType.MINUTES.idOffset
        val trafSlot0 =
            NotificationHelper.BASE_NOTIFICATION_ID +
                (0 * NotificationHelper.SLOT_OFFSET) +
                UsageNotificationType.TRAFFIC.idOffset
        val minSlot1 =
            NotificationHelper.BASE_NOTIFICATION_ID +
                (1 * NotificationHelper.SLOT_OFFSET) +
                UsageNotificationType.MINUTES.idOffset
        val trafSlot1 =
            NotificationHelper.BASE_NOTIFICATION_ID +
                (1 * NotificationHelper.SLOT_OFFSET) +
                UsageNotificationType.TRAFFIC.idOffset

        val ids =
            setOf(
                minSlot0,
                trafSlot0,
                minSlot1,
                trafSlot1,
                NotificationHelper.AOD_SUMMARY_NOTIFICATION_ID,
                NotificationHelper.GROUP_SUMMARY_NOTIFICATION_ID,
            )
        assertEquals(6, ids.size)
    }

    @Test
    fun `sendAodSummaryNotification posts notification when permission granted`() {
        whenever(permissionManager.hasNotificationPermission()).thenReturn(true)

        val success = helper.sendAodSummaryNotification("AOD Title", "AOD Line", "Expanded text")

        assertTrue(success)
        val shadowNotificationManager = shadowOf(notificationManager)
        assertEquals(1, shadowNotificationManager.allNotifications.size)
        val notification = shadowNotificationManager.allNotifications[0]
        val title =
            notification.extras
                .getCharSequence(android.app.Notification.EXTRA_TITLE)
                .toString()
        val text =
            notification.extras
                .getCharSequence(android.app.Notification.EXTRA_TEXT)
                .toString()
        val bigText =
            notification.extras
                .getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)
                .toString()
        assertEquals("AOD Title", title)
        assertEquals("AOD Line", text)
        assertEquals("Expanded text", bigText)
        assertTrue((notification.flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0)
        assertEquals(android.app.Notification.VISIBILITY_PUBLIC, notification.visibility)
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

        val blockedChannel =
            NotificationChannel(
                Constants.Notifications.AOD_SUMMARY_CHANNEL_ID_V2,
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
                Constants.Notifications.AOD_SUMMARY_CHANNEL_ID_V2,
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

        val nullServiceHelper =
            NotificationHelper(nullServiceContext, navigationProvider, permissionManager)

        nullServiceHelper.sendUsageNotification("Title", "Message", 0, UsageNotificationType.TRAFFIC)
        nullServiceHelper.sendUsageNotification("Title", "Message", 0, UsageNotificationType.TRAFFIC)

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

        val nullServiceHelper =
            NotificationHelper(nullServiceContext, navigationProvider, permissionManager)

        assertFalse(nullServiceHelper.sendAodSummaryNotification("Title", "Line", "Text"))
        nullServiceHelper.cancelAodSummaryNotification()
        assertFalse(nullServiceHelper.isAodSummaryAvailable())
        assertFalse(nullServiceHelper.isAodSummaryNotificationActive())
    }

    @Test
    fun `isAodSummaryNotificationActive returns true when notification is posted`() {
        whenever(permissionManager.hasNotificationPermission()).thenReturn(true)
        helper.sendAodSummaryNotification("Title", "Line", "Expanded")

        assertTrue(helper.isAodSummaryNotificationActive())
    }

    @Test
    fun `isAodSummaryNotificationActive returns false when no notification posted`() {
        assertFalse(helper.isAodSummaryNotificationActive())
    }

    @Test
    fun `isAodSummaryNotificationActive returns false when NotificationManager is null`() {
        val nullServiceContext =
            object : android.content.ContextWrapper(context) {
                override fun getSystemService(name: String): Any? = null

                override fun getSystemServiceName(serviceClass: Class<*>): String? = null
            }
        val nullServiceHelper =
            NotificationHelper(nullServiceContext, navigationProvider, permissionManager)
        assertFalse(nullServiceHelper.isAodSummaryNotificationActive())
    }

    @Test
    fun `createNotificationChannel handles runtime exceptions from notification manager gracefully`() {
        val mockManager: NotificationManager = mock()
        doThrow(RuntimeException("Platform error"))
            .whenever(mockManager)
            .createNotificationChannel(any())
        whenever(mockManager.getNotificationChannel(any())).thenReturn(null)

        val wrappedContext =
            object : android.content.ContextWrapper(context) {
                override fun getSystemService(name: String): Any? {
                    if (name == Context.NOTIFICATION_SERVICE) {
                        return mockManager
                    }
                    return super.getSystemService(name)
                }

                override fun getSystemServiceName(serviceClass: Class<*>): String? {
                    if (serviceClass == NotificationManager::class.java) {
                        return Context.NOTIFICATION_SERVICE
                    }
                    return super.getSystemServiceName(serviceClass)
                }
            }

        val testHelper =
            NotificationHelper(wrappedContext, navigationProvider, permissionManager)

        verify(mockManager, org.mockito.kotlin.times(2)).createNotificationChannel(any())

        whenever(permissionManager.hasNotificationPermission()).thenReturn(true)
        assertFalse(testHelper.isAodSummaryAvailable())
        assertFalse(testHelper.sendAodSummaryNotification("Title", "Line", "Expanded"))
    }
}
