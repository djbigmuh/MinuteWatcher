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

        private fun createNotificationChannel() {
            val manager = notificationManager ?: return
            val channel =
                NotificationChannel(
                    Constants.Notifications.CHANNEL_ID,
                    context.getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            manager.createNotificationChannel(channel)
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
        }
    }
