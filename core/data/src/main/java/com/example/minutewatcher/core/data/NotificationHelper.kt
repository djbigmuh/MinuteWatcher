package com.example.minutewatcher.core.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.domain.NavigationProvider
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.Constants.IntentExtras
import com.example.minutewatcher.core.resources.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Helper class for managing and sending application usage notifications.
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
        private val notificationManager: NotificationManager? =
            try {
                context.getSystemService(NotificationManager::class.java)
            } catch (e: Exception) {
                AppLogger.w("Failed to obtain NotificationManager: ${e.message}")
                null
            }

        @Volatile
        private var hasLoggedMissingService = false

        init {
            createNotificationChannel()
        }

    @Suppress("TooGenericExceptionCaught")
        private fun createNotificationChannel() {
            val manager = notificationManager ?: return
            val channel =
                NotificationChannel(
                    Constants.Notifications.CHANNEL_ID,
                    context.getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.notification_channel_desc)
                }
            val aodChannel =
                NotificationChannel(
                    Constants.Notifications.AOD_SUMMARY_CHANNEL_ID,
                    context.getString(R.string.notification_channel_aod_summary_name),
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    description = context.getString(R.string.notification_channel_aod_summary_desc)
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
        try {
            manager.createNotificationChannel(channel)
        } catch (e: RuntimeException) {
            AppLogger.e("Failed to create main notification channel", e)
        }
        try {
            manager.createNotificationChannel(aodChannel)
        } catch (e: RuntimeException) {
            AppLogger.e("Failed to create AOD summary notification channel", e)
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
            type: Int,
        ) {
            val manager = notificationManager
            if (manager == null) {
                if (!hasLoggedMissingService) {
                    hasLoggedMissingService = true
                    AppLogger.w("NotificationManager is unavailable; skipping notification")
                }
                return
            }

            try {
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
                    return
                }

                val intent =
                    Intent().setClassName(context.packageName, navigationProvider.mainActivityClassName).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra(IntentExtras.SIM_SLOT, simSlot)
                    }

                val pendingIntent =
                    PendingIntent.getActivity(
                        context,
                        BASE_REQUEST_CODE + (simSlot * SLOT_OFFSET) + type,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )

                val notification =
                    NotificationCompat
                        .Builder(context, Constants.Notifications.CHANNEL_ID)
                        .setContentTitle(title)
                        .setContentText(message)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setColor(ContextCompat.getColor(context, R.color.primary))
                        .setAutoCancel(true)
                        .setContentIntent(pendingIntent)
                        .setGroup("usage_monitor_group")
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .build()

                val notificationId = BASE_NOTIFICATION_ID + (simSlot * SLOT_OFFSET) + type
                manager.notify(notificationId, notification)
            } catch (e: SecurityException) {
                AppLogger.w("Notification permission denied: ${e.message}")
            } catch (e: RuntimeException) {
                AppLogger.e("Failed to send notification (runtime error)", e)
            }
        }

        private fun isAodPostingAllowed(manager: NotificationManager): Boolean {
            val hasPermission = permissionManager.hasNotificationPermission()
            val aodChannel = manager.getNotificationChannel(Constants.Notifications.AOD_SUMMARY_CHANNEL_ID)
            val channelBlocked = aodChannel != null && aodChannel.importance == NotificationManager.IMPORTANCE_NONE

            return if (!hasPermission || !areNotificationsEnabledSafe()) {
                val reason = if (!hasPermission) "permission not granted" else "disabled for app"
                AppLogger.d("AOD summary notification skipped: $reason")
                false
            } else if (channelBlocked) {
                AppLogger.d("AOD summary notification skipped: channel blocked")
                false
            } else {
                true
            }
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
            val manager =
                notificationManager ?: run {
                    if (!hasLoggedMissingService) {
                        hasLoggedMissingService = true
                        AppLogger.w("NotificationManager is unavailable; skipping AOD summary notification")
                    }
                    return false
                }

            return try {
                if (isAodPostingAllowed(manager)) {
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
                            .Builder(context, Constants.Notifications.AOD_SUMMARY_CHANNEL_ID)
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
                    val channel = manager.getNotificationChannel(Constants.Notifications.AOD_SUMMARY_CHANNEL_ID)
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

        companion object {
            private const val BASE_REQUEST_CODE = 100
            private const val BASE_NOTIFICATION_ID = 1000
            private const val SLOT_OFFSET = 10

            /**
             * Outside the range of existing usage notifications
             * (BASE_NOTIFICATION_ID/BASE_REQUEST_CODE and slot offsets)
             * to avoid collision.
             */
            const val AOD_SUMMARY_NOTIFICATION_ID = 900
            const val AOD_SUMMARY_REQUEST_CODE = 90
        }
    }
