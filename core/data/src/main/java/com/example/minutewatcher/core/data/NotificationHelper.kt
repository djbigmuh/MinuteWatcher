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
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val navigationProvider: NavigationProvider,
        private val permissionManager: PermissionManager,
    ) {
        private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        init {
            createNotificationChannel()
        }

        private fun createNotificationChannel() {
            val channel =
                NotificationChannel(
                    Constants.Notifications.CHANNEL_ID,
                    context.getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            notificationManager.createNotificationChannel(channel)
        }

        fun sendUsageNotification(
            title: String,
            message: String,
            simSlot: Int,
            type: Int,
        ) {
            try {
                if (!permissionManager.hasNotificationPermission() || !areNotificationsEnabledSafe()) {
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
                notificationManager.notify(notificationId, notification)
            } catch (e: SecurityException) {
                AppLogger.w("Notification permission denied: ${e.message}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Failed to send notification", e)
            }
        }

        private fun areNotificationsEnabledSafe(): Boolean =
            try {
                NotificationManagerCompat.from(context).areNotificationsEnabled()
            } catch (e: SecurityException) {
                AppLogger.w("SecurityException while checking notification status: ${e.message}")
                false
            } catch (e: android.os.DeadObjectException) {
                AppLogger.w("NotificationManager service died: ${e.message}")
                false
            } catch (e: RuntimeException) {
                // Catches other system service issues and unexpected errors
                AppLogger.w("Unexpected error checking notification status: ${e.javaClass.simpleName}: ${e.message}")
                false
            }

        companion object {
            private const val BASE_REQUEST_CODE = 100
            private const val BASE_NOTIFICATION_ID = 1000
            private const val SLOT_OFFSET = 10
        }
    }
