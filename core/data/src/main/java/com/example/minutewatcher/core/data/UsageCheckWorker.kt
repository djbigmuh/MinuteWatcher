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
import com.example.minutewatcher.core.model.GlobalSnapshot
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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

@HiltWorker
/**
 * UsageCheckWorker encapsulates a single background check cycle
 * for limit usage across all active SIM cards.
 */
@Suppress("TooManyFunctions")
class UsageCheckWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val deps: UsageWorkerDependencies,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : CoroutineWorker(context, params) {
        /**
         * Performs periodic usage check.
         *
         * API compliance: on AOSP refresh() completes in milliseconds.
         * 2 min timeout is a protective buffer to compensate for DataStore degradation on custom firmwares.
         *
         * Custom firmware deviation: timeout indicates DataStore degradation
         * on custom firmware. This is a firmware problem.
         * Strategy: Result.retry() — the system will repeat the Worker later.
         */
        override suspend fun doWork(): Result =
            withContext(ioDispatcher) {
                withTimeoutOrNull(WORKER_TIMEOUT_MS.milliseconds) {
                    executeWork()
                } ?: run {
                    AppLogger.e("UsageCheckWorker: timed out after ${WORKER_TIMEOUT_MS}ms")
                    Result.retry()
                }
            }

        private suspend fun executeWork(): Result =
            try {
                withTimeoutOrNull(WORKER_REFRESH_TIMEOUT_MS.milliseconds) {
                    deps.usageUseCase.refreshOperatorInfo()
                } ?: run {
                    AppLogger.w("UsageCheckWorker: refreshOperatorInfo timed out, continuing")
                }
                checkUsageAndNotify()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                AppLogger.w("UsageCheckWorker: Network or storage error, retrying. ${e.message}")
                Result.retry()
            } catch (e: SecurityException) {
                AppLogger.e("UsageCheckWorker: Permission error (unexpected)", e)
                Result.failure()
            } catch (e: Exception) {
                AppLogger.e("UsageCheckWorker failed due to unexpected error", e)
                Result.failure()
            }

        // @Suppress("ReturnCount") is required for early exits on permission checks and timeouts.
        @Suppress("ReturnCount")
        private suspend fun checkUsageAndNotify(): Result {
            deps.trafficTracker.updateTrafficUsage()

            tryPerformAutoReset()

            val refreshResult =
                withTimeoutOrNull(WORKER_REFRESH_TIMEOUT_MS.milliseconds) {
                    deps.usageStateRepository.refresh()
                }

            if (refreshResult == null) {
                AppLogger.e("UsageCheckWorker: refresh timed out after ${WORKER_REFRESH_TIMEOUT_MS}ms")
                return Result.retry()
            }

            return when (refreshResult) {
                is RefreshResult.Timeout -> handleRefreshTimeout(refreshResult)
                else -> {
                    processSnapshot(refreshResult.snapshot, shouldNotify = true)
                    Result.success()
                }
            }
        }

        private suspend fun tryPerformAutoReset() {
            try {
                val allReset = deps.autoResetUseCase.performAutoReset(shouldNotify = false)
                if (!allReset) {
                    AppLogger.w("UsageCheckWorker: Auto-reset partially failed, continuing.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("UsageCheckWorker: Auto-reset failed with error, continuing", e)
            }
        }

        private suspend fun handleRefreshTimeout(refreshResult: RefreshResult.Timeout): Result {
            val snapshot = refreshResult.snapshot
            return if (snapshot.timestamp == 0L) {
                AppLogger.w("UsageCheckWorker: no data loaded, scheduling retry")
                Result.retry()
            } else {
                AppLogger.w(
                    "UsageCheckWorker: refresh timed out, proceeding with stale snapshot " +
                        "from ${snapshot.timestamp} (notifications disabled)",
                )
                processSnapshot(snapshot, shouldNotify = false)
                Result.success()
            }
        }

        private suspend fun processSnapshot(
            snapshot: GlobalSnapshot,
            shouldNotify: Boolean,
        ) {
            deps.dataChangeNotifier.notifyDataChanged(updateWidget = true)

            if (!shouldNotify) return

            val appSettings =
                fetchAppSettingsWithRetry() ?: run {
                    AppLogger.w("UsageCheckWorker: skipping notifications, settings load failed")
                    return
                }

            for (simSnapshot in snapshot.simSnapshots) {
                processActiveSimSnapshot(simSnapshot, appSettings)
            }
        }

        private suspend fun processActiveSimSnapshot(
            simSnapshot: SimSnapshot,
            appSettings: AppSettings,
        ) {
            try {
                withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                    processSimSnapshot(simSnapshot, appSettings)
                } ?: run {
                    AppLogger.w("UsageCheckWorker: timeout processing SIM slot ${simSnapshot.slot}, skipping")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("UsageCheckWorker: error processing SIM slot ${simSnapshot.slot}", e)
            }
        }

        private suspend fun fetchAppSettingsWithRetry(): AppSettings? {
            val appSettingsResult =
                deps.appSettingsRepository.getAppSettings().firstWithTimeout() ?: run {
                    AppLogger.w("UsageCheckWorker: AppSettings read timed out, retrying with 10s...")
                    deps.appSettingsRepository
                        .getAppSettings()
                        .firstWithTimeout(Constants.Flow.LONG_TIMEOUT_MS)
                }

            return appSettingsResult ?: run {
                AppLogger.w("UsageCheckWorker: settings load failed")
                null
            }
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
            val daysRemaining = DateUtils.getDaysRemainingInMonth(simUsage.autoResetDay, now)

            simSnapshot.entities[PackageType.MINUTES]?.let {
                checkMinutesUsage(it, daysRemaining, identity, appSettings)
            }
            simSnapshot.entities[PackageType.DATA]?.let {
                checkTrafficUsage(it, daysRemaining, identity, appSettings)
            }
        }

        private suspend fun checkMinutesUsage(
            entity: SemanticEntity,
            daysRemaining: Int,
            identity: String,
            appSettings: AppSettings,
        ) {
            if ((entity.limit <= 0) || !appSettings.notifyLowMinutes) return

            val usagePercentage = ((entity.used.toDouble() * HUNDRED) / entity.limit).toInt()
            val remainingPercentage = HUNDRED - usagePercentage

            val notifiedExhausted =
                deps.simNotificationFlagsRepository
                    .getNotifiedExhaustedMinutes(identity)
                    .firstWithTimeout() ?: run {
                    AppLogger.w("UsageCheckWorker: timeout reading exhausted minutes flag for $identity, skipping")
                    return
                }
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
                    deps.simNotificationFlagsRepository.setNotifiedExhaustedMinutes(identity, notified = false)
                }
                handleLowMinutesAlert(
                    entity,
                    remainingPercentage,
                    daysRemaining,
                    identity,
                )
            }
        }

        /**
         * Handles the low minutes alert based on remaining percentage.
         * Triggered when remaining is below [Constants.LOW_THRESHOLD_PERCENT_INT].
         */
        private suspend fun handleLowMinutesAlert(
            entity: SemanticEntity,
            remPct: Int,
            daysRem: Int,
            identity: String,
        ) {
            val notifiedLow =
                deps.simNotificationFlagsRepository
                    .getNotifiedLowMinutes(identity)
                    .firstWithTimeout() ?: run {
                    AppLogger.w("UsageCheckWorker: timeout reading low minutes flag for $identity, skipping")
                    return
                }

            if ((remPct < Constants.LOW_THRESHOLD_PERCENT_INT) && !notifiedLow && (entity.remaining > 0)) {
                val levelRes =
                    if (remPct < Constants.CRITICAL_THRESHOLD_PERCENT_INT) {
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
            } else if ((remPct >= Constants.LOW_THRESHOLD_PERCENT_INT) && notifiedLow) {
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
            daysRemaining: Int,
            identity: String,
            appSettings: AppSettings,
        ) {
            if ((entity.limit <= 0) || !appSettings.notifyLowTraffic) return

            val usagePercentage = ((entity.used.toDouble() * HUNDRED) / entity.limit).toInt()
            val remainingPercentage = HUNDRED - usagePercentage
            val remainingGb = (entity.remaining.toDouble() / Constants.BYTES_PER_GB).toFloat()

            val notifiedExhausted =
                deps.simNotificationFlagsRepository
                    .getNotifiedExhaustedTraffic(identity)
                    .firstWithTimeout() ?: run {
                    AppLogger.w("UsageCheckWorker: timeout reading exhausted traffic flag for $identity, skipping")
                    return
                }
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
                    remainingPercentage,
                    remainingGb,
                    daysRemaining,
                    identity,
                )
            }
        }

        /**
         * Handles the low traffic alert based on remaining percentage.
         * Triggered when remaining is below [Constants.LOW_THRESHOLD_PERCENT_INT].
         */
        private suspend fun handleLowTrafficAlert(
            entity: SemanticEntity,
            remPct: Int,
            remGb: Float,
            daysRem: Int,
            identity: String,
        ) {
            val notifiedLow =
                deps.simNotificationFlagsRepository
                    .getNotifiedLowTraffic(identity)
                    .firstWithTimeout() ?: run {
                    AppLogger.w("UsageCheckWorker: timeout reading low traffic flag for $identity, skipping")
                    return
                }

            if ((remPct < Constants.LOW_THRESHOLD_PERCENT_INT) && !notifiedLow && (entity.remaining > 0)) {
                val levelRes =
                    if (remPct < Constants.CRITICAL_THRESHOLD_PERCENT_INT) {
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
            } else if ((remPct >= Constants.LOW_THRESHOLD_PERCENT_INT) && notifiedLow) {
                deps.simNotificationFlagsRepository.setNotifiedLowTraffic(identity, notified = false)
            }
        }

        companion object {
            private const val HUNDRED = 100

            private const val NOTIFICATION_TYPE_MINUTES = 0
            private const val NOTIFICATION_TYPE_TRAFFIC = 1

            /**
             * Общий таймаут воркера. Превышение означает зависание
             * на кастомной прошивке. На AOSP все операции завершаются
             * в пределах нескольких секунд.
             */
            private const val WORKER_TIMEOUT_MS = 120_000L // 2 минуты

            private const val WORKER_REFRESH_TIMEOUT_MS = 30_000L
        }
    }
