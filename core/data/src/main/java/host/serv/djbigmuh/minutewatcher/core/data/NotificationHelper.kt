package host.serv.djbigmuh.minutewatcher.core.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import host.serv.djbigmuh.minutewatcher.core.android.util.AppLocaleContexts
import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import host.serv.djbigmuh.minutewatcher.core.common.util.PermissionManager
import host.serv.djbigmuh.minutewatcher.core.domain.NavigationProvider
import host.serv.djbigmuh.minutewatcher.core.model.Constants
import host.serv.djbigmuh.minutewatcher.core.model.Constants.IntentExtras
import host.serv.djbigmuh.minutewatcher.core.resources.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Type of usage notification (Minutes or Traffic) with its unique ID offset.
 */
enum class UsageNotificationType(
    val idOffset: Int,
) {
    MINUTES(0),
    TRAFFIC(1),
}

/**
 * Helper class for managing and sending application usage notifications.
 *
 * ## ID Non-Collision Invariants
 * To prevent notification ID collisions across different notification categories and SIM slots:
 * - **Usage Notifications**: Calculated as [BASE_NOTIFICATION_ID] (1000) + (`simSlot` * [SLOT_OFFSET] (10)) + [UsageNotificationType.idOffset].
 *   - Slot 0 Minutes: 1000, Traffic: 1001
 *   - Slot 1 Minutes: 1010, Traffic: 1011
 *   - Slot N Minutes/Traffic: 1000 + N * 10 + {0, 1}
 * - **Always-on Display (AOD) Summary Notification**: [AOD_SUMMARY_NOTIFICATION_ID] (900).
 * - **Usage Group Summary Notification**: [GROUP_SUMMARY_NOTIFICATION_ID] (950).
 *
 * These ID ranges are strictly disjoint (900, 950, and 1000+), ensuring zero collisions.
 *
 * Defensive contract: Notification delivery and helper initialization must never
 * cause the application to crash. All platform calls to [NotificationManager] are
 * safely handled.
 */
@Singleton
class NotificationHelper
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
    private val navigationProvider: NavigationProvider,
    private val permissionManager: PermissionManager,
) {
    companion object {
        internal const val BASE_REQUEST_CODE = 100
        internal const val BASE_NOTIFICATION_ID = 1000
        internal const val SLOT_OFFSET = 10

        /**
         * ID for Always-on Display summary notification.
         * Invariant: Must be outside the range of usage notifications and group summary notifications
         * to prevent ID collisions.
         */
        const val AOD_SUMMARY_NOTIFICATION_ID = 900
        const val AOD_SUMMARY_REQUEST_CODE = 90

        /**
         * ID for usage group summary notification.
         * Invariant: Must be in a unique range distinct from individual usage notifications and AOD summary.
         */
        const val GROUP_SUMMARY_NOTIFICATION_ID = 950
        internal const val GROUP_SUMMARY_REQUEST_CODE = 95

        internal const val GROUP_KEY = "usage_monitor_group"
    }

    private val notificationManager: NotificationManager? =
        try {
            context.getSystemService(NotificationManager::class.java)
        } catch (e: Exception) {
            AppLogger.w("Failed to obtain NotificationManager: ${e.message}")
            null
        }

    @Volatile
    private var hasLoggedMissingService = false

    /**
     * Creates or updates notification channels with localized names and descriptions.
     */
    @Suppress("TooGenericExceptionCaught")
    fun ensureChannels() {
        val manager = notificationManager ?: return
        val locContext = AppLocaleContexts.forAppLocale(context)
        val channel =
            NotificationChannel(
                Constants.Notifications.CHANNEL_ID,
                locContext.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = locContext.getString(R.string.notification_channel_desc)
            }
        val aodChannel =
            NotificationChannel(
                Constants.Notifications.AOD_SUMMARY_CHANNEL_ID_V2,
                locContext.getString(R.string.notification_channel_aod_summary_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = locContext.getString(R.string.notification_channel_aod_summary_desc)
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        try {
            manager.createNotificationChannel(channel)
        } catch (e: RuntimeException) {
            AppLogger.e("Failed to create/update main notification channel", e)
        }
        try {
            manager.createNotificationChannel(aodChannel)
        } catch (e: RuntimeException) {
            AppLogger.e("Failed to create/update V2 AOD summary notification channel", e)
        }
        try {
            manager.deleteNotificationChannel(Constants.Notifications.AOD_SUMMARY_CHANNEL_ID)
        } catch (e: RuntimeException) {
            AppLogger.w("Failed to delete legacy AOD summary notification channel: ${e.message}")
        }
    }

    /**
     * Sends a usage notification.
     *
     * Defensive contract: notification delivery should never cause app crash.
     * Platform implementations of NotificationManager may throw various RuntimeExceptions.
     * Suppressing TooGenericExceptionCaught as RuntimeException is caught defensively.
     */
    @Suppress("TooGenericExceptionCaught")
    fun sendUsageNotification(
        title: String,
        message: String,
        simSlot: Int,
        type: UsageNotificationType,
    ): Boolean {
        ensureChannels()
        val manager = notificationManager
        if (manager == null) {
            if (!hasLoggedMissingService) {
                hasLoggedMissingService = true
                AppLogger.w("NotificationManager is unavailable; skipping notification")
            }
            return false
        }

        return try {
            val hasPermission = permissionManager.hasNotificationPermission()
            val areEnabled = if (hasPermission) areNotificationsEnabledSafe() else false

            if (!hasPermission || !areEnabled) {
                val reason =
                    if (!hasPermission) {
                        "notification permission not granted"
                    } else {
                        "notifications disabled for app"
                    }
                AppLogger.d("Notification skipped: $reason")
                return false
            }

            val channel = manager.getNotificationChannel(Constants.Notifications.CHANNEL_ID)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                AppLogger.d("Notification skipped: channel is blocked (IMPORTANCE_NONE)")
                return false
            }

            val notificationId = BASE_NOTIFICATION_ID + (simSlot * SLOT_OFFSET) + type.idOffset
            val notification = buildUsageNotification(title, message, simSlot, type)
            manager.notify(notificationId, notification)

            val summaryNotification = buildGroupSummaryNotification()
            manager.notify(GROUP_SUMMARY_NOTIFICATION_ID, summaryNotification)
            true
        } catch (e: SecurityException) {
            AppLogger.w("Notification permission denied: ${e.message}")
            false
        } catch (e: RuntimeException) {
            AppLogger.e("Failed to send notification (runtime error)", e)
            false
        }
    }

    private fun buildUsageNotification(
        title: String,
        message: String,
        simSlot: Int,
        type: UsageNotificationType,
    ): android.app.Notification {
        val intent =
            Intent().setClassName(context.packageName, navigationProvider.mainActivityClassName).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(IntentExtras.SIM_SLOT, simSlot)
            }

        val pendingIntent =
            PendingIntent.getActivity(
                context,
                BASE_REQUEST_CODE + (simSlot * SLOT_OFFSET) + type.idOffset,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        return NotificationCompat
            .Builder(context, Constants.Notifications.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.primary))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setGroup(GROUP_KEY)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun buildGroupSummaryNotification(): android.app.Notification {
        val locContext = AppLocaleContexts.forAppLocale(context)
        val summaryIntent =
            Intent().setClassName(context.packageName, navigationProvider.mainActivityClassName).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

        val summaryPendingIntent =
            PendingIntent.getActivity(
                context,
                GROUP_SUMMARY_REQUEST_CODE,
                summaryIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        return NotificationCompat
            .Builder(context, Constants.Notifications.CHANNEL_ID)
            .setContentTitle(locContext.getString(R.string.notification_channel_name))
            .setContentText(locContext.getString(R.string.notification_channel_desc))
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.primary))
            .setContentIntent(summaryPendingIntent)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .build()
    }

    /**
     * Sends or updates the persistent silent Always-on Display summary notification.
     * Content is visible on locked secure screens (`VISIBILITY_PUBLIC`).
     * Function is opt-in (disabled by default).
     *
     * @return true if notification was posted successfully, false otherwise.
     */
    @Suppress("TooGenericExceptionCaught")
    fun sendAodSummaryNotification(
        title: String,
        contentLine: String,
        expandedText: String,
    ): Boolean {
        ensureChannels()
        val manager =
            notificationManager ?: run {
                if (!hasLoggedMissingService) {
                    hasLoggedMissingService = true
                    AppLogger.w("NotificationManager is unavailable; skipping AOD summary notification")
                }
                return false
            }

        return try {
            if (isAodSummaryAvailable()) {
                val intent =
                    Intent().setClassName(context.packageName, navigationProvider.mainActivityClassName).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }

                val pendingIntent =
                    PendingIntent.getActivity(
                        context,
                        AOD_SUMMARY_REQUEST_CODE,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )

                val notification =
                    NotificationCompat
                        .Builder(context, Constants.Notifications.AOD_SUMMARY_CHANNEL_ID_V2)
                        .setContentTitle(title)
                        .setContentText(contentLine)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(expandedText))
                        .setSmallIcon(R.drawable.ic_notification)
                        .setColor(ContextCompat.getColor(context, R.color.primary))
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .setShowWhen(false)
                        .setCategory(NotificationCompat.CATEGORY_STATUS)
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .setContentIntent(pendingIntent)
                        .build()

                manager.notify(AOD_SUMMARY_NOTIFICATION_ID, notification)
                true
            } else {
                false
            }
        } catch (e: SecurityException) {
            AppLogger.w("AOD summary notification permission denied: ${e.message}")
            false
        } catch (e: RuntimeException) {
            AppLogger.e("Failed to send AOD summary notification (runtime error)", e)
            false
        }
    }

    /**
     * Checks whether the AOD summary notification is currently active in the notification manager.
     */
    @Suppress("TooGenericExceptionCaught")
    fun isAodSummaryNotificationActive(): Boolean {
        val manager = notificationManager ?: return false
        return try {
            manager.activeNotifications.any { it.id == AOD_SUMMARY_NOTIFICATION_ID }
        } catch (e: SecurityException) {
            AppLogger.w("Error checking active AOD notifications: SecurityException: ${e.message}")
            false
        } catch (e: RuntimeException) {
            AppLogger.w("Error checking active AOD notifications: RuntimeException: ${e.message}")
            false
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun cancelAodSummaryNotification() {
        val manager = notificationManager ?: return
        try {
            manager.cancel(AOD_SUMMARY_NOTIFICATION_ID)
        } catch (e: SecurityException) {
            AppLogger.w("Failed to cancel AOD summary notification: ${e.message}")
        } catch (e: RuntimeException) {
            AppLogger.e("Failed to cancel AOD summary notification (runtime error)", e)
        }
    }

    /**
     * Checks whether AOD summary notification can be posted.
     *
     * @return true if NotificationManager is available, notification permission is granted,
     * notifications are enabled, and AOD summary channel exists and is not blocked.
     */
    @Suppress("TooGenericExceptionCaught")
    fun isAodSummaryAvailable(): Boolean {
        val manager = notificationManager ?: return false
        return try {
            if (!permissionManager.hasNotificationPermission() || !areNotificationsEnabledSafe()) {
                false
            } else {
                val channel = manager.getNotificationChannel(Constants.Notifications.AOD_SUMMARY_CHANNEL_ID_V2)
                channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE
            }
        } catch (e: SecurityException) {
            AppLogger.w("Error checking AOD summary availability: SecurityException: ${e.message}")
            false
        } catch (e: RuntimeException) {
            AppLogger.w("Error checking AOD summary availability: RuntimeException: ${e.message}")
            false
        }
    }

    /**
     * Safely checks if notifications are enabled.
     *
     * Defensive contract: checking notification status should never cause app crash.
     * Platform implementations of NotificationManagerCompat may throw various RuntimeExceptions.
     * Suppressing TooGenericExceptionCaught as RuntimeException is caught defensively.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun areNotificationsEnabledSafe(): Boolean =
        try {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        } catch (e: SecurityException) {
            AppLogger.w("Error checking notification status: SecurityException: ${e.message}")
            false
        } catch (e: RuntimeException) {
            AppLogger.w("Error checking notification status: RuntimeException: ${e.message}")
            false
        }
}
