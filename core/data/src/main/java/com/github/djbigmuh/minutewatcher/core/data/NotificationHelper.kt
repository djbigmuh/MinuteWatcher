package com.github.djbigmuh.minutewatcher.core.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.github.djbigmuh.minutewatcher.core.android.util.AppLocaleContexts
import com.github.djbigmuh.minutewatcher.core.common.util.AppLogger
import com.github.djbigmuh.minutewatcher.core.common.util.PermissionManager
import com.github.djbigmuh.minutewatcher.core.domain.NavigationProvider
import com.github.djbigmuh.minutewatcher.core.model.Constants
import com.github.djbigmuh.minutewatcher.core.model.Constants.IntentExtras
import com.github.djbigmuh.minutewatcher.core.resources.R
import dagger.hilt.android.qualifiers.ApplicationContext
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
            internal const val AOD_SUMMARY_NOTIFICATION_ID = 900
            internal const val AOD_SUMMARY_REQUEST_CODE = 90

            /**
             * ID for usage group summary notification.
             * Invariant: Must be in a unique range distinct from individual usage notifications and AOD summary.
             */
            internal const val GROUP_SUMMARY_NOTIFICATION_ID = 950
            internal const val GROUP_SUMMARY_REQUEST_CODE = 95

            internal const val GROUP_KEY = "usage_monitor_group"
        }

        private val notificationManager: NotificationManager? =
            try {
                context.getSystemService(NotificationManager::class.java)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // Operation must complete without propagating exception for notification manager acquisition.
                AppLogger.w("Failed to obtain NotificationManager: ${e.message}")
                null
            }

        @Volatile
        private var hasLoggedMissingService = false

        @Volatile
        private var initializedLocaleTag: String? = null

        @Volatile
        private var legacyChannelDeleted = false

        private val channelsLock = Any()

        /**
         * Creates or updates notification channels with localized names and descriptions.
         */
        fun ensureChannels() {
            val manager = notificationManager ?: return
            val locContext = AppLocaleContexts.forAppLocale(context)
            val locales = locContext.resources.configuration.locales
            val currentLocaleTag = if (!locales.isEmpty) locales.get(0).toLanguageTag() else ""

            if (initializedLocaleTag == currentLocaleTag) {
                return
            }

            synchronized(channelsLock) {
                if (initializedLocaleTag == currentLocaleTag) {
                    return
                }

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
                    manager.createNotificationChannel(aodChannel)

                    if (!legacyChannelDeleted) {
                        try {
                            manager.deleteNotificationChannel(Constants.Notifications.AOD_SUMMARY_CHANNEL_ID)
                        } catch (
                            @Suppress("TooGenericExceptionCaught") e: Exception,
                        ) {
                            // Operation must complete without propagating exception for legacy channel deletion.
                            AppLogger.w("Failed to delete legacy AOD summary notification channel: ${e.message}")
                        }
                        legacyChannelDeleted = true
                    }

                    initializedLocaleTag = currentLocaleTag
                } catch (
                    // Operation must complete without propagating exception for notification channel creation;
                    // channels are retried on the next notification.
                    @Suppress("TooGenericExceptionCaught") e: RuntimeException,
                ) {
                    AppLogger.e("Failed to create or update notification channels", e)
                }
            }
        }

        /**
         * Sends a usage notification.
         *
         * Defensive contract: notification delivery should never cause app crash.
         * Platform implementations of NotificationManager may throw various RuntimeExceptions.
         * Suppressing TooGenericExceptionCaught as RuntimeException is caught defensively.
         */
        fun sendUsageNotification(
            title: String,
            message: String,
            simSlot: Int,
            type: UsageNotificationType,
        ): Boolean {
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
                val areEnabled = hasPermission && areNotificationsEnabledSafe()

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
            } catch (
                @Suppress("TooGenericExceptionCaught") e: RuntimeException,
            ) {
                AppLogger.e("Failed to send notification (runtime error)", e)
                false
            }
        }

        private fun buildUsageNotification(
            title: String,
            message: String,
            simSlot: Int,
            type: UsageNotificationType,
        ): Notification {
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
                .setSmallIcon(R.drawable.ic_stat_minutewatcher)
                .setColor(ContextCompat.getColor(context, R.color.primary))
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setGroup(GROUP_KEY)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
        }

        private fun buildGroupSummaryNotification(): Notification {
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
                .setSmallIcon(R.drawable.ic_stat_minutewatcher)
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
        fun sendAodSummaryNotification(
            title: String,
            contentLine: String,
            expandedText: String,
        ): Boolean {
            val manager =
                notificationManager ?: run {
                    if (!hasLoggedMissingService) {
                        hasLoggedMissingService = true
                        AppLogger.w("NotificationManager is unavailable; skipping AOD summary notification")
                    }
                    return false
                }

            return try {
                // AOD channel availability is a settings capability check and intentionally
                // does not include runtime permission. Delivery must check permission separately.
                if (!permissionManager.hasNotificationPermission() || !areNotificationsEnabledSafe()) {
                    AppLogger.d("AOD summary notification skipped: notification permission or app notifications disabled")
                    return false
                }

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
                            .setSmallIcon(R.drawable.ic_stat_minutewatcher)
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
            } catch (
                // Operation must complete without propagating exception for AOD summary delivery;
                // it is best-effort and must not reach the tracking pipeline.
                @Suppress("TooGenericExceptionCaught") e: RuntimeException,
            ) {
                AppLogger.e("Failed to send AOD summary notification (runtime error)", e)
                false
            }
        }

        /**
         * Checks whether the AOD summary notification is currently active in the notification manager.
         */
        fun isAodSummaryNotificationActive(): Boolean {
            val manager = notificationManager ?: return false
            return try {
                manager.activeNotifications.any { it.id == AOD_SUMMARY_NOTIFICATION_ID }
            } catch (e: SecurityException) {
                AppLogger.w("Error checking active AOD notifications: SecurityException: ${e.message}")
                false
            } catch (
                // Operation must complete without propagating exception for the AOD notification state query;
                // it is best-effort and degrades to false.
                @Suppress("TooGenericExceptionCaught") e: RuntimeException,
            ) {
                AppLogger.w("Error checking active AOD notifications: RuntimeException: ${e.message}")
                false
            }
        }

        fun cancelAodSummaryNotification() {
            val manager = notificationManager ?: return
            try {
                manager.cancel(AOD_SUMMARY_NOTIFICATION_ID)
            } catch (e: SecurityException) {
                AppLogger.w("Failed to cancel AOD summary notification: ${e.message}")
            } catch (
                // Operation must complete without propagating exception for AOD notification cancellation;
                // it is best-effort.
                @Suppress("TooGenericExceptionCaught") e: RuntimeException,
            ) {
                AppLogger.e("Failed to cancel AOD summary notification (runtime error)", e)
            }
        }

        /**
         * Checks whether the AOD summary notification channel is available for configuration.
         *
         * This deliberately does not require notification permission or globally enabled notifications:
         * the user grants runtime permission only after enabling an alert in settings.
         *
         * @return true if NotificationManager and the AOD summary channel are available and the channel is not blocked.
         */
        fun isAodSummaryAvailable(): Boolean {
            val manager = notificationManager ?: return false
            return try {
                val channel = manager.getNotificationChannel(Constants.Notifications.AOD_SUMMARY_CHANNEL_ID_V2)
                channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE
            } catch (e: SecurityException) {
                AppLogger.w("Error checking AOD summary availability: SecurityException: ${e.message}")
                false
            } catch (
                // Operation must complete without propagating exception for the AOD availability probe;
                // it is best-effort and degrades to false.
                @Suppress("TooGenericExceptionCaught") e: RuntimeException,
            ) {
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
        private fun areNotificationsEnabledSafe(): Boolean =
            try {
                NotificationManagerCompat.from(context).areNotificationsEnabled()
            } catch (e: SecurityException) {
                AppLogger.w("Error checking notification status: SecurityException: ${e.message}")
                false
            } catch (
                @Suppress("TooGenericExceptionCaught") e: RuntimeException,
            ) {
                AppLogger.w("Error checking notification status: RuntimeException: ${e.message}")
                false
            }
    }
