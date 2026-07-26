package com.example.minutewatcher.core.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.minutewatcher.core.domain.SettingsRepository
import com.example.minutewatcher.core.domain.SimRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.R
import com.example.minutewatcher.core.model.SemanticEntity
import com.example.minutewatcher.core.model.util.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@HiltWorker
class UsageCheckWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val simRepository: SimRepository,
        private val settingsRepository: SettingsRepository,
        private val resetManager: ResetManager,
        private val trafficTracker: TrafficTracker,
        private val notificationHelper: NotificationHelper,
        private val updateCoordinator: UpdateCoordinator,
        private val usageStateRepository: UsageStateRepository,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result =
            withContext(Dispatchers.IO) {
                if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_PHONE_STATE) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    AppLogger.d("READ_PHONE_STATE not granted, skipping UsageCheckWorker")
                    return@withContext Result.success()
                }
                try {
                    checkUsageAndNotify()
                    Result.success()
                } catch (e: Exception) {
                    AppLogger.e("Error in UsageCheckWorker", e)
                    Result.retry()
                }
            }

        private suspend fun checkUsageAndNotify() {
            // Refresh usage data and get a fresh snapshot
            val snapshot = usageStateRepository.refresh()

            // Pass snapshot to reset manager to avoid redundant identity lookups
            resetManager.performAutoReset(simRepository, settingsRepository, snapshot)

            trafficTracker.updateTrafficUsage()

            // Refresh again after possible reset and traffic update to have latest values for notification check
            val finalSnapshot = usageStateRepository.refresh()

            updateCoordinator.notifyDataChanged(applicationContext, updateWidget = true)

            val appSettings = settingsRepository.observeAppSettings().value

            for (simSnapshot in finalSnapshot.simSnapshots) {
                if (!simSnapshot.isActive) continue

                val slot = simSnapshot.slot
                val identity = simRepository.getIdentity(slot)

                // We need the reset day for progress calculation.
                // It's in the semantic entity metadata now.
                val minutesEntity = simSnapshot.entities[PackageType.MINUTES]
                val resetDay =
                    minutesEntity
                        ?.metadata
                        ?.get("reset_day")
                        ?.toString()
                        ?.toIntOrNull() ?: 1

                val monthProgress = DateUtils.getMonthProgressPercentage(resetDay)
                val daysRemaining = DateUtils.getDaysRemainingInMonth(resetDay)

                minutesEntity?.let {
                    checkMinutesUsage(it, monthProgress, daysRemaining, identity, appSettings)
                }
                simSnapshot.entities[PackageType.DATA]?.let {
                    checkTrafficUsage(it, monthProgress, daysRemaining, identity, appSettings)
                }
            }
        }

        private suspend fun checkMinutesUsage(
            entity: SemanticEntity,
            monthProgress: Int,
            daysRemaining: Int,
            identity: String,
            appSettings: com.example.minutewatcher.core.model.AppSettings,
        ) {
            val limit = entity.limit
            if (limit <= 0) return

            val used = entity.used
            val remaining = entity.remaining
            val usagePercentage = (used * 100 / limit).toInt()
            val remainingPercentage = 100 - usagePercentage
            val simSlot = entity.simSlot
            val operator = entity.operatorName

            if (appSettings.notifyLowMinutes) {
                val notifiedExhausted = simRepository.getNotifiedExhaustedMinutes(identity).first()
                if (remaining <= 0) {
                    if (!notifiedExhausted) {
                        notificationHelper.sendUsageNotification(
                            applicationContext.getString(R.string.notification_minutes_title, operator),
                            applicationContext.getString(R.string.notification_minutes_exhausted_body),
                            simSlot,
                            0,
                        )
                        simRepository.setNotifiedExhaustedMinutes(identity, notified = true)
                    }
                } else {
                    if (notifiedExhausted) simRepository.setNotifiedExhaustedMinutes(identity, notified = false)

                    val notifiedLow = simRepository.getNotifiedLowMinutes(identity).first()
                    if ((usagePercentage > monthProgress) && !notifiedLow) {
                        val level =
                            if (remainingPercentage < 10) {
                                applicationContext.getString(R.string.usage_level_critical)
                            } else {
                                applicationContext.getString(R.string.usage_level_low)
                            }
                        val remainingText = FormatUtils.formatMinutesPlural(applicationContext, remaining.toInt())
                        val daysText = FormatUtils.formatDaysPlural(applicationContext, daysRemaining)

                        notificationHelper.sendUsageNotification(
                            applicationContext.getString(R.string.notification_minutes_title, operator),
                            applicationContext.getString(
                                R.string.notification_minutes_body,
                                level,
                                remainingText,
                                remainingPercentage,
                                daysText,
                            ),
                            simSlot,
                            0,
                        )
                        simRepository.setNotifiedLowMinutes(identity, notified = true)
                    } else if (usagePercentage <= monthProgress) {
                        if (notifiedLow) simRepository.setNotifiedLowMinutes(identity, notified = false)
                    }
                }
            }
        }

        private suspend fun checkTrafficUsage(
            entity: SemanticEntity,
            monthProgress: Int,
            daysRemaining: Int,
            identity: String,
            appSettings: com.example.minutewatcher.core.model.AppSettings,
        ) {
            val limit = entity.limit
            if (limit <= 0) return

            val used = entity.used
            val remaining = entity.remaining
            val usagePercentage = (used * 100 / limit).toInt()
            val remainingPercentage = 100 - usagePercentage
            val simSlot = entity.simSlot
            val operator = entity.operatorName

            val remainingGb = remaining.toFloat() / (1024 * 1024 * 1024)

            if (appSettings.notifyLowTraffic) {
                val notifiedExhausted = simRepository.getNotifiedExhaustedTraffic(identity).first()
                if (remaining <= 0) {
                    if (!notifiedExhausted) {
                        notificationHelper.sendUsageNotification(
                            applicationContext.getString(R.string.notification_traffic_title, operator),
                            applicationContext.getString(R.string.notification_traffic_exhausted_body),
                            simSlot,
                            1,
                        )
                        simRepository.setNotifiedExhaustedTraffic(identity, notified = true)
                    }
                } else {
                    if (notifiedExhausted) simRepository.setNotifiedExhaustedTraffic(identity, notified = false)

                    val notifiedLow = simRepository.getNotifiedLowTraffic(identity).first()
                    if ((usagePercentage > monthProgress) && !notifiedLow) {
                        val level = applicationContext.getString(R.string.usage_level_low)
                        val daysText = FormatUtils.formatDaysPlural(applicationContext, daysRemaining)

                        notificationHelper.sendUsageNotification(
                            applicationContext.getString(R.string.notification_traffic_title, operator),
                            applicationContext.getString(
                                R.string.notification_traffic_body,
                                level,
                                remainingGb,
                                remainingPercentage,
                                daysText,
                            ),
                            simSlot,
                            1,
                        )
                        simRepository.setNotifiedLowTraffic(identity, notified = true)
                    } else if (usagePercentage <= monthProgress) {
                        if (notifiedLow) simRepository.setNotifiedLowTraffic(identity, notified = false)
                    }
                }
            }
        }
    }
