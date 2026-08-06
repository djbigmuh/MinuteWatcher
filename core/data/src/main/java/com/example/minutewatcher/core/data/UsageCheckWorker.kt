package com.example.minutewatcher.core.data

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.minutewatcher.core.common.di.IoDispatcher
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.FormatUtils
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.domain.RefreshResult
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.SemanticEntity
import com.example.minutewatcher.core.model.SimSnapshot
import com.example.minutewatcher.core.model.util.DateUtils
import com.example.minutewatcher.core.resources.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

@HiltWorker
class UsageCheckWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val deps: UsageWorkerDependencies,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result =
            withContext(ioDispatcher) {
                try {
                    // Refresh operator info periodically
                    deps.usageUseCase.refreshOperatorInfo()

                    return@withContext checkUsageAndNotify()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: java.io.IOException) {
                    AppLogger.w("UsageCheckWorker: Network or storage error, retrying. ${e.message}")
                    Result.retry()
                } catch (e: SecurityException) {
                    AppLogger.e("UsageCheckWorker: Permission error (unexpected)", e)
                    Result.failure()
                } catch (e: Exception) {
                    AppLogger.e("UsageCheckWorker failed due to unexpected error", e)
                    Result.failure()
                }
            }

        private suspend fun checkUsageAndNotify(): Result {
            deps.trafficTracker.updateTrafficUsage()

            // Perform auto-reset logic. We skip internal notification here because we do a single
            // unified refresh and notify below.
            val isAutoResetSuccess =
                try {
                    deps.autoResetUseCase.performAutoReset(shouldNotify = false)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Auto-reset failed in UsageCheckWorker, retrying", e)
                    false
                }

            if (!isAutoResetSuccess) return Result.retry()

            // Single refresh to capture all recent changes (traffic update and potential auto-reset)
            val refreshResult = deps.usageStateRepository.refresh()
            if (refreshResult !is RefreshResult.Timeout) {
                val finalSnapshot = refreshResult.snapshot
                deps.dataChangeNotifier.notifyDataChanged(updateWidget = true)

                // Cleanup orphaned widget settings for non-existent SIMs
                val activeIdentities = finalSnapshot.simSnapshots.map { it.identity }
                deps.simWidgetVisibilityRepository.clearOrphanedSettings(activeIdentities)

                val appSettingsResult =
                    deps.appSettingsRepository.getAppSettings().firstWithTimeout() ?: run {
                        AppLogger.w("UsageCheckWorker: AppSettings read timed out, retrying with 10s...")
                        deps.appSettingsRepository
                            .getAppSettings()
                            .firstWithTimeout(Constants.Flow.LONG_TIMEOUT_MS)
                    }

                val appSettings =
                    appSettingsResult ?: run {
                        AppLogger.w("UsageCheckWorker: AppSettings read failed after retry, using defaults")
                        AppSettings()
                    }
                for (simSnapshot in finalSnapshot.simSnapshots) {
                    if (simSnapshot.isActive) {
                        processSimSnapshot(simSnapshot, appSettings)
                    }
                }
            } else {
                AppLogger.w(
                    "UsageCheckWorker: Data refresh timed out. Skipping notifications to avoid stale alerts.",
                )
                deps.dataChangeNotifier.notifyDataChanged(updateWidget = true)
            }

            return Result.success()
        }

        private suspend fun processSimSnapshot(
            simSnapshot: SimSnapshot,
            appSettings: AppSettings,
        ) {
            val identity = simSnapshot.identity
            var simUsageResult =
                deps.simUsageRepository
                    .getSimUsage(identity)
                    .firstWithTimeout()
            if (simUsageResult == null) {
                AppLogger.w("UsageCheckWorker: getSimUsage timed out for $identity, retrying with 10s...")
                simUsageResult =
                    deps.simUsageRepository
                        .getSimUsage(identity)
                        .firstWithTimeout(Constants.Flow.LONG_TIMEOUT_MS)
            }

            val simUsage =
                simUsageResult ?: run {
                    AppLogger.e(
                        "UsageCheckWorker: getSimUsage failed for $identity after retry, skipping notifications",
                    )
                    return
                }

            val now = deps.timeProvider.now()

            val monthProgress = DateUtils.getMonthProgressPercentage(simUsage.autoResetDay, now)
            val daysRemaining = DateUtils.getDaysRemainingInMonth(simUsage.autoResetDay, now)

            simSnapshot.entities[PackageType.MINUTES]?.let {
                checkMinutesUsage(it, monthProgress, daysRemaining, identity, appSettings)
            }
            simSnapshot.entities[PackageType.DATA]?.let {
                checkTrafficUsage(it, monthProgress, daysRemaining, identity, appSettings)
            }
        }

        private suspend fun checkMinutesUsage(
            entity: SemanticEntity,
            monthProgress: Int,
            daysRemaining: Int,
            identity: String,
            appSettings: AppSettings,
        ) {
            if ((entity.limit <= 0) || !appSettings.notifyLowMinutes) return

            val usagePercentage = (entity.used.toDouble() * HUNDRED / entity.limit).toInt()
            val remainingPercentage = HUNDRED - usagePercentage

            val notifiedExhausted =
                deps.simNotificationFlagsRepository
                    .getNotifiedExhaustedMinutes(identity)
                    .firstWithTimeout() ?: false
            if (entity.remaining <= 0) {
                if (!notifiedExhausted) {
                    sendNotificationWithBodyRes(
                        entity.operatorName,
                        R.string.notification_minutes_exhausted_body,
                        entity.simSlot,
                        NOTIFICATION_TYPE_MINUTES,
                    )
                    deps.simNotificationFlagsRepository.setNotifiedExhaustedMinutes(identity, notified = true)
                }
            } else {
                if (notifiedExhausted) {
                    deps.simNotificationFlagsRepository.setNotifiedExhaustedMinutes(identity, false)
                }
                handleLowMinutesAlert(
                    entity,
                    usagePercentage,
                    remainingPercentage,
                    monthProgress,
                    daysRemaining,
                    identity,
                )
            }
        }

        private suspend fun handleLowMinutesAlert(
            entity: SemanticEntity,
            usagePct: Int,
            remPct: Int,
            monthProg: Int,
            daysRem: Int,
            identity: String,
        ) {
            val notifiedLow =
                deps.simNotificationFlagsRepository
                    .getNotifiedLowMinutes(identity)
                    .firstWithTimeout() ?: false
            if (usagePct > monthProg && !notifiedLow) {
                val levelRes =
                    if (remPct < CRITICAL_THRESHOLD) {
                        R.string.usage_level_critical
                    } else {
                        R.string.usage_level_low
                    }
                val level = applicationContext.getString(levelRes)
                val remText = FormatUtils.formatMinutesPlural(applicationContext, entity.remaining.toInt())
                val daysText = FormatUtils.formatDaysPlural(applicationContext, daysRem)

                val body =
                    applicationContext.getString(
                        R.string.notification_minutes_body,
                        level,
                        remText,
                        remPct,
                        daysText,
                    )
                sendNotificationWithBody(entity.operatorName, body, entity.simSlot, NOTIFICATION_TYPE_MINUTES)
                deps.simNotificationFlagsRepository.setNotifiedLowMinutes(identity, notified = true)
            } else if (usagePct <= monthProg && notifiedLow) {
                deps.simNotificationFlagsRepository.setNotifiedLowMinutes(identity, notified = false)
            }
        }

        private fun sendNotificationWithBodyRes(
            operator: String,
            bodyResId: Int,
            slot: Int,
            type: Int,
        ) {
            val body = applicationContext.getString(bodyResId)
            sendNotificationWithBody(operator, body, slot, type)
        }

        private fun sendNotificationWithBody(
            operator: String,
            body: String,
            slot: Int,
            type: Int,
        ) {
            val titleRes =
                if (type == NOTIFICATION_TYPE_MINUTES) {
                    R.string.notification_minutes_title
                } else {
                    R.string.notification_traffic_title
                }
            val title = applicationContext.getString(titleRes, operator)
            deps.notificationHelper.sendUsageNotification(title, body, slot, type)
        }

        private suspend fun checkTrafficUsage(
            entity: SemanticEntity,
            monthProgress: Int,
            daysRemaining: Int,
            identity: String,
            appSettings: AppSettings,
        ) {
            if ((entity.limit <= 0) || !appSettings.notifyLowTraffic) return

            val usagePercentage = (entity.used.toDouble() * HUNDRED / entity.limit).toInt()
            val remainingPercentage = HUNDRED - usagePercentage
            val remainingGb = (entity.remaining.toDouble() / Constants.BYTES_PER_GB).toFloat()

            val notifiedExhausted =
                deps.simNotificationFlagsRepository
                    .getNotifiedExhaustedTraffic(identity)
                    .firstWithTimeout() ?: false
            if (entity.remaining <= 0) {
                if (!notifiedExhausted) {
                    sendNotificationWithBodyRes(
                        entity.operatorName,
                        R.string.notification_traffic_exhausted_body,
                        entity.simSlot,
                        NOTIFICATION_TYPE_TRAFFIC,
                    )
                    deps.simNotificationFlagsRepository.setNotifiedExhaustedTraffic(identity, notified = true)
                }
            } else {
                if (notifiedExhausted) {
                    deps.simNotificationFlagsRepository.setNotifiedExhaustedTraffic(
                        identity,
                        notified = false,
                    )
                }
                handleLowTrafficAlert(
                    entity,
                    usagePercentage,
                    remainingPercentage,
                    remainingGb,
                    monthProgress,
                    daysRemaining,
                    identity,
                )
            }
        }

        private suspend fun handleLowTrafficAlert(
            entity: SemanticEntity,
            usagePct: Int,
            remPct: Int,
            remGb: Float,
            monthProg: Int,
            daysRem: Int,
            identity: String,
        ) {
            val notifiedLow =
                deps.simNotificationFlagsRepository
                    .getNotifiedLowTraffic(identity)
                    .firstWithTimeout() ?: false
            if (usagePct > monthProg && !notifiedLow) {
                val levelRes =
                    if (remPct < CRITICAL_THRESHOLD) {
                        R.string.usage_level_critical
                    } else {
                        R.string.usage_level_low
                    }
                val level = applicationContext.getString(levelRes)
                val daysText = FormatUtils.formatDaysPlural(applicationContext, daysRem)
                val body =
                    applicationContext.getString(
                        R.string.notification_traffic_body,
                        level,
                        remGb,
                        remPct,
                        daysText,
                    )
                sendNotificationWithBody(entity.operatorName, body, entity.simSlot, NOTIFICATION_TYPE_TRAFFIC)
                deps.simNotificationFlagsRepository.setNotifiedLowTraffic(identity, notified = true)
            } else if (usagePct <= monthProg && notifiedLow) {
                deps.simNotificationFlagsRepository.setNotifiedLowTraffic(identity, notified = false)
            }
        }

        companion object {
            private const val HUNDRED = 100
            private const val CRITICAL_THRESHOLD = 10

            private const val NOTIFICATION_TYPE_MINUTES = 0
            private const val NOTIFICATION_TYPE_TRAFFIC = 1
        }
    }
