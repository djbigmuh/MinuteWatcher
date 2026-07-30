package com.example.minutewatcher.core.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.minutewatcher.core.common.di.IoDispatcher
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.AutoResetUseCase
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimNotificationFlagsRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.UsageUseCase
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.resources.R
import com.example.minutewatcher.core.model.SemanticEntity
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.model.util.DateUtils
import com.example.minutewatcher.core.android.util.FormatUtils
import com.example.minutewatcher.core.common.util.TimeProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@HiltWorker
class UsageCheckWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val simIdentityRepository: SimIdentityRepository,
        private val simUsageRepository: SimUsageRepository,
        private val simNotificationFlagsRepository: SimNotificationFlagsRepository,
        private val appSettingsRepository: AppSettingsRepository,
        private val autoResetUseCase: AutoResetUseCase,
        private val trafficTracker: TrafficTracker,
        private val notificationHelper: NotificationHelper,
        private val dataChangeNotifier: DataChangeNotifier,
        private val usageStateRepository: UsageStateRepository,
        private val usageUseCase: UsageUseCase,
        private val timeProvider: TimeProvider,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result =
            withContext(ioDispatcher) {
                try {
                    // Refresh operator info periodically (if permissions allow)
                    usageUseCase.refreshOperatorInfo()

                    checkUsageAndNotify()
                    Result.success()
                } catch (e: kotlinx.coroutines.CancellationException) {
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

        private suspend fun checkUsageAndNotify() {
            usageStateRepository.refresh()
            autoResetUseCase.performAutoReset(usageStateRepository.observeSnapshot().value)
            trafficTracker.updateTrafficUsage()

            val finalSnapshot = usageStateRepository.refresh()
            dataChangeNotifier.notifyDataChanged(updateWidget = true)

            val appSettings = appSettingsRepository.observeAppSettings().value
            for (simSnapshot in finalSnapshot.simSnapshots) {
                if (simSnapshot.isActive) {
                    processSimSnapshot(simSnapshot, appSettings)
                }
            }
        }

        private suspend fun processSimSnapshot(
            simSnapshot: com.example.minutewatcher.core.model.SimSnapshot,
            appSettings: com.example.minutewatcher.core.model.AppSettings,
        ) {
            val identity = simIdentityRepository.getIdentity(simSnapshot.slot)
            val simUsage = simUsageRepository.getSimUsage(identity).first()
            val now = timeProvider.now()

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
            appSettings: com.example.minutewatcher.core.model.AppSettings,
        ) {
            if (entity.limit <= 0 || !appSettings.notifyLowMinutes) return

            val usagePercentage = (entity.used * HUNDRED / entity.limit).toInt()
            val remainingPercentage = HUNDRED - usagePercentage

            val notifiedExhausted = simNotificationFlagsRepository.getNotifiedExhaustedMinutes(identity).first()
            if (entity.remaining <= 0) {
                if (!notifiedExhausted) {
                    sendNotificationWithBodyRes(entity.operatorName, R.string.notification_minutes_exhausted_body, entity.simSlot, 0)
                    simNotificationFlagsRepository.setNotifiedExhaustedMinutes(identity, notified = true)
                }
            } else {
                if (notifiedExhausted) simNotificationFlagsRepository.setNotifiedExhaustedMinutes(identity, notified = false)
                handleLowMinutesAlert(entity, usagePercentage, remainingPercentage, monthProgress, daysRemaining, identity)
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
            val notifiedLow = simNotificationFlagsRepository.getNotifiedLowMinutes(identity).first()
            if (usagePct > monthProg && !notifiedLow) {
                val levelRes = if (remPct < CRITICAL_THRESHOLD) R.string.usage_level_critical else R.string.usage_level_low
                val level = applicationContext.getString(levelRes)
                val remText = FormatUtils.formatMinutesPlural(applicationContext, entity.remaining.toInt())
                val daysText = FormatUtils.formatDaysPlural(applicationContext, daysRem)

                val body = applicationContext.getString(R.string.notification_minutes_body, level, remText, remPct, daysText)
                sendNotificationWithBody(entity.operatorName, body, entity.simSlot, 0)
                simNotificationFlagsRepository.setNotifiedLowMinutes(identity, notified = true)
            } else if (usagePct <= monthProg && notifiedLow) {
                simNotificationFlagsRepository.setNotifiedLowMinutes(identity, notified = false)
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
            val titleRes = if (type == 0) R.string.notification_minutes_title else R.string.notification_traffic_title
            val title = applicationContext.getString(titleRes, operator)
            notificationHelper.sendUsageNotification(title, body, slot, type)
        }

        private suspend fun checkTrafficUsage(
            entity: SemanticEntity,
            monthProgress: Int,
            daysRemaining: Int,
            identity: String,
            appSettings: com.example.minutewatcher.core.model.AppSettings,
        ) {
            if (entity.limit <= 0 || !appSettings.notifyLowTraffic) return

            val usagePercentage = (entity.used * HUNDRED / entity.limit).toInt()
            val remainingPercentage = HUNDRED - usagePercentage
            val remainingGb = entity.remaining.toFloat() / BYTES_IN_GB

            val notifiedExhausted = simNotificationFlagsRepository.getNotifiedExhaustedTraffic(identity).first()
            if (entity.remaining <= 0) {
                if (!notifiedExhausted) {
                    sendNotificationWithBodyRes(entity.operatorName, R.string.notification_traffic_exhausted_body, entity.simSlot, 1)
                    simNotificationFlagsRepository.setNotifiedExhaustedTraffic(identity, notified = true)
                }
            } else {
                if (notifiedExhausted) simNotificationFlagsRepository.setNotifiedExhaustedTraffic(identity, notified = false)
                handleLowTrafficAlert(entity, usagePercentage, remainingPercentage, remainingGb, monthProgress, daysRemaining, identity)
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
            val notifiedLow = simNotificationFlagsRepository.getNotifiedLowTraffic(identity).first()
            if (usagePct > monthProg && !notifiedLow) {
                val levelRes = if (remPct < CRITICAL_THRESHOLD) R.string.usage_level_critical else R.string.usage_level_low
                val level = applicationContext.getString(levelRes)
                val daysText = FormatUtils.formatDaysPlural(applicationContext, daysRem)
                val body = applicationContext.getString(R.string.notification_traffic_body, level, remGb, remPct, daysText)
                sendNotificationWithBody(entity.operatorName, body, entity.simSlot, 1)
                simNotificationFlagsRepository.setNotifiedLowTraffic(identity, notified = true)
            } else if (usagePct <= monthProg && notifiedLow) {
                simNotificationFlagsRepository.setNotifiedLowTraffic(identity, notified = false)
            }
        }

        companion object {
            private const val HUNDRED = 100
            private const val CRITICAL_THRESHOLD = 10
            private const val BYTES_IN_GB = 1024.0f * 1024.0f * 1024.0f
        }
    }



