package host.serv.djbigmuh.minutewatcher.core.data

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import host.serv.djbigmuh.minutewatcher.core.android.util.AppLocaleContexts
import host.serv.djbigmuh.minutewatcher.core.common.di.IoDispatcher
import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import host.serv.djbigmuh.minutewatcher.core.common.util.FormatUtils
import host.serv.djbigmuh.minutewatcher.core.common.util.firstWithTimeout
import host.serv.djbigmuh.minutewatcher.core.domain.RefreshResult
import host.serv.djbigmuh.minutewatcher.core.model.AppSettings
import host.serv.djbigmuh.minutewatcher.core.model.Constants
import host.serv.djbigmuh.minutewatcher.core.model.GlobalSnapshot
import host.serv.djbigmuh.minutewatcher.core.model.PackageType
import host.serv.djbigmuh.minutewatcher.core.model.SemanticEntity
import host.serv.djbigmuh.minutewatcher.core.model.SimSnapshot
import host.serv.djbigmuh.minutewatcher.core.model.util.DateUtils
import host.serv.djbigmuh.minutewatcher.core.model.util.UsageLevelResolver
import host.serv.djbigmuh.minutewatcher.core.model.util.UsageStatus
import host.serv.djbigmuh.minutewatcher.core.resources.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

/**
 * UsageCheckWorker encapsulates a single background check cycle
 * for limit usage across all active SIM cards.
 * Notification limits and remaining days are calculated directly from the SIM snapshot.
 */
@HiltWorker
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
            @Suppress("TooGenericExceptionCaught")
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
                // Operation must complete without propagating exception for worker execution.
                AppLogger.e("UsageCheckWorker failed due to unexpected error", e)
                Result.failure()
            }

        private suspend fun checkUsageAndNotify(): Result =
            when (deps.trafficTracker.updateTrafficUsage()) {
                is TrafficUpdateOutcome.TimedOut -> {
                    AppLogger.w("UsageCheckWorker: Traffic update timed out, retrying.")
                    Result.retry()
                }

                TrafficUpdateOutcome.Completed,
                TrafficUpdateOutcome.Skipped,
                is TrafficUpdateOutcome.Failed,
                -> {
                    tryPerformAutoReset()
                    executeRefreshAndProcess()
                }
            }

        private suspend fun executeRefreshAndProcess(): Result {
            val refreshResult =
                withTimeoutOrNull(WORKER_REFRESH_TIMEOUT_MS.milliseconds) {
                    deps.usageStateRepository.refresh()
                } ?: run {
                    AppLogger.e("UsageCheckWorker: refresh timed out after ${WORKER_REFRESH_TIMEOUT_MS}ms")
                    null
                }

            if (refreshResult == null) {
                return Result.retry()
            }

            return when (refreshResult) {
                is RefreshResult.Success -> {
                    processSnapshot(refreshResult.snapshot, shouldNotify = true)
                    Result.success()
                }

                is RefreshResult.Timeout -> handleRefreshTimeout(refreshResult)
                is RefreshResult.Failure -> {
                    AppLogger.e("UsageCheckWorker: refresh failed", refreshResult.cause)
                    Result.retry()
                }
            }
        }

        private suspend fun tryPerformAutoReset() {
            @Suppress("TooGenericExceptionCaught")
            try {
                val allReset = deps.autoResetUseCase.performAutoReset(shouldNotify = false)
                if (!allReset) {
                    AppLogger.w("UsageCheckWorker: Auto-reset partially failed, continuing.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Operation must complete without propagating exception for auto reset step.
                AppLogger.e("UsageCheckWorker: Auto-reset failed with error, continuing", e)
            }
        }

        private suspend fun handleRefreshTimeout(refreshResult: RefreshResult.Timeout): Result {
            val snapshot = refreshResult.snapshot
            return if (snapshot.timestamp == Constants.Data.EMPTY_TIMESTAMP) {
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
            @Suppress("TooGenericExceptionCaught")
            try {
                withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                    processSimSnapshot(simSnapshot, appSettings)
                } ?: run {
                    AppLogger.w("UsageCheckWorker: timeout processing SIM slot ${simSnapshot.slot}, skipping")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Operation must complete without propagating exception for SIM snapshot processing.
                AppLogger.e("UsageCheckWorker: error processing SIM slot ${simSnapshot.slot}", e)
            }
        }

        private suspend fun fetchAppSettingsWithRetry(): AppSettings? {
            val timeoutMs = Constants.Flow.LONG_TIMEOUT_MS
            val appSettingsResult =
                deps.appSettingsRepository.getAppSettings().firstWithTimeout() ?: run {
                    AppLogger.w("UsageCheckWorker: AppSettings read timed out, retrying with ${timeoutMs}ms...")
                    deps.appSettingsRepository
                        .getAppSettings()
                        .firstWithTimeout(timeoutMs)
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
            val now = deps.timeProvider.now()
            val zoneId = deps.timeProvider.zoneId()
            val daysRemaining =
                if (simSnapshot.autoReset) {
                    if (now >= Constants.Data.MIN_VALID_TIMESTAMP) {
                        DateUtils.getDaysRemainingInMonth(simSnapshot.autoResetDay, now, zoneId)
                    } else {
                        AppLogger.w("UsageCheckWorker: Skipping days remaining calculation due to un-synchronized device time ($now)")
                        0
                    }
                } else {
                    0
                }

            simSnapshot.entities[PackageType.MINUTES]?.let {
                checkMinutesUsage(it, daysRemaining, identity, appSettings, simSnapshot.autoReset)
            }
            simSnapshot.entities[PackageType.DATA]?.let {
                checkTrafficUsage(it, daysRemaining, identity, appSettings, simSnapshot.autoReset)
            }
        }

        private suspend fun checkMinutesUsage(
            entity: SemanticEntity,
            daysRemaining: Int,
            identity: String,
            appSettings: AppSettings,
            autoReset: Boolean,
        ) {
            if ((entity.limit <= 0) || !appSettings.notifyLowMinutes) {
                resetStaleMinutesFlags(identity)
                return
            }

            val notifiedExhausted =
                deps.simNotificationFlagsRepository
                    .getNotifiedExhaustedMinutes(identity)
                    .firstWithTimeout() ?: run {
                    AppLogger.w("UsageCheckWorker: timeout reading exhausted minutes flag for $identity, skipping")
                    return
                }
            if (entity.remaining <= 0) {
                if (!notifiedExhausted) {
                    val bodyResId = getMinutesExhaustedResId(autoReset)
                    val sent =
                        sendNotificationWithBodyRes(
                            entity.operatorName,
                            bodyResId,
                            entity.simSlot,
                            UsageNotificationType.MINUTES,
                        )
                    if (sent) {
                        deps.simNotificationFlagsRepository.setNotifiedExhaustedMinutes(identity, notified = true)
                    }
                }
            } else {
                if (notifiedExhausted) {
                    deps.simNotificationFlagsRepository.setNotifiedExhaustedMinutes(identity, notified = false)
                }
                checkUsageLevelAndNotify(
                    entity,
                    daysRemaining,
                    identity,
                    autoReset,
                    PackageType.MINUTES,
                )
            }
        }

        private suspend fun checkTrafficUsage(
            entity: SemanticEntity,
            daysRemaining: Int,
            identity: String,
            appSettings: AppSettings,
            autoReset: Boolean,
        ) {
            if ((entity.limit <= 0) || !appSettings.notifyLowTraffic) {
                resetStaleTrafficFlags(identity)
                return
            }

            val notifiedExhausted =
                deps.simNotificationFlagsRepository
                    .getNotifiedExhaustedTraffic(identity)
                    .firstWithTimeout() ?: run {
                    AppLogger.w("UsageCheckWorker: timeout reading exhausted traffic flag for $identity, skipping")
                    return
                }
            if (entity.remaining <= 0) {
                if (!notifiedExhausted) {
                    val bodyResId = getTrafficExhaustedResId(autoReset)
                    val sent =
                        sendNotificationWithBodyRes(
                            entity.operatorName,
                            bodyResId,
                            entity.simSlot,
                            UsageNotificationType.TRAFFIC,
                        )
                    if (sent) {
                        deps.simNotificationFlagsRepository.setNotifiedExhaustedTraffic(identity, notified = true)
                    }
                }
            } else {
                if (notifiedExhausted) {
                    deps.simNotificationFlagsRepository.setNotifiedExhaustedTraffic(
                        identity,
                        notified = false,
                    )
                }
                checkUsageLevelAndNotify(
                    entity,
                    daysRemaining,
                    identity,
                    autoReset,
                    PackageType.DATA,
                )
            }
        }

        /**
         * Resets stale minute notification flags when checks are inactive,
         * so that re-enabling notifications or setting limits is not suppressed by stale flags.
         */
        private suspend fun resetStaleMinutesFlags(identity: String) {
            val low = deps.simNotificationFlagsRepository.getNotifiedLowMinutes(identity).firstWithTimeout()
            if (low == true) {
                deps.simNotificationFlagsRepository.setNotifiedLowMinutes(identity, false)
            }
            val critical = deps.simNotificationFlagsRepository.getNotifiedCriticalMinutes(identity).firstWithTimeout()
            if (critical == true) {
                deps.simNotificationFlagsRepository.setNotifiedCriticalMinutes(identity, false)
            }
            val exhausted = deps.simNotificationFlagsRepository.getNotifiedExhaustedMinutes(identity).firstWithTimeout()
            if (exhausted == true) {
                deps.simNotificationFlagsRepository.setNotifiedExhaustedMinutes(identity, false)
            }
        }

        /**
         * Resets stale traffic notification flags when checks are inactive,
         * so that re-enabling notifications or setting limits is not suppressed by stale flags.
         */
        private suspend fun resetStaleTrafficFlags(identity: String) {
            val low = deps.simNotificationFlagsRepository.getNotifiedLowTraffic(identity).firstWithTimeout()
            if (low == true) {
                deps.simNotificationFlagsRepository.setNotifiedLowTraffic(identity, false)
            }
            val critical = deps.simNotificationFlagsRepository.getNotifiedCriticalTraffic(identity).firstWithTimeout()
            if (critical == true) {
                deps.simNotificationFlagsRepository.setNotifiedCriticalTraffic(identity, false)
            }
            val exhausted = deps.simNotificationFlagsRepository.getNotifiedExhaustedTraffic(identity).firstWithTimeout()
            if (exhausted == true) {
                deps.simNotificationFlagsRepository.setNotifiedExhaustedTraffic(identity, false)
            }
        }

        /**
         * Handles usage level check and notification escalation for minutes and traffic.
         */
        private suspend fun checkUsageLevelAndNotify(
            entity: SemanticEntity,
            daysRem: Int,
            identity: String,
            autoReset: Boolean,
            packageType: PackageType,
        ) {
            val level = UsageLevelResolver.resolve(entity.remaining, entity.limit)
            val isMinutes = packageType == PackageType.MINUTES

            val notifiedLow = fetchLowFlag(identity, isMinutes) ?: return
            val notifiedCritical = fetchCriticalFlag(identity, isMinutes) ?: return

            handleNotificationDispatch(
                entity,
                daysRem,
                autoReset,
                packageType,
                level,
                notifiedLow,
                notifiedCritical,
                identity,
            )
            updateFlagsIfNeeded(identity, isMinutes, level, notifiedLow, notifiedCritical)
        }

        private suspend fun fetchLowFlag(
            identity: String,
            isMinutes: Boolean,
        ): Boolean? =
            (
                if (isMinutes) {
                    deps.simNotificationFlagsRepository.getNotifiedLowMinutes(identity)
                } else {
                    deps.simNotificationFlagsRepository.getNotifiedLowTraffic(identity)
                }
            ).firstWithTimeout()

        private suspend fun fetchCriticalFlag(
            identity: String,
            isMinutes: Boolean,
        ): Boolean? =
            (
                if (isMinutes) {
                    deps.simNotificationFlagsRepository.getNotifiedCriticalMinutes(identity)
                } else {
                    deps.simNotificationFlagsRepository.getNotifiedCriticalTraffic(identity)
                }
            ).firstWithTimeout()

        private suspend fun handleNotificationDispatch(
            entity: SemanticEntity,
            daysRem: Int,
            autoReset: Boolean,
            packageType: PackageType,
            level: UsageStatus,
            notifiedLow: Boolean,
            notifiedCritical: Boolean,
            identity: String,
        ) {
            val isMinutes = packageType == PackageType.MINUTES
            if (level == UsageStatus.CRITICAL && !notifiedCritical) {
                val sent = sendUsageNotificationForLevel(entity, daysRem, autoReset, packageType, UsageStatus.CRITICAL)
                if (sent) {
                    setCriticalFlag(identity, isMinutes, true)
                }
            } else if (level == UsageStatus.LOW && !notifiedLow && !notifiedCritical) {
                val sent = sendUsageNotificationForLevel(entity, daysRem, autoReset, packageType, UsageStatus.LOW)
                if (sent) {
                    setLowFlag(identity, isMinutes, true)
                }
            }
        }

        private suspend fun updateFlagsIfNeeded(
            identity: String,
            isMinutes: Boolean,
            level: UsageStatus,
            notifiedLow: Boolean,
            notifiedCritical: Boolean,
        ) {
            val targetCritical = level == UsageStatus.CRITICAL
            if (notifiedCritical && !targetCritical) {
                setCriticalFlag(identity, isMinutes, false)
            }

            val targetLow = level == UsageStatus.LOW
            if (notifiedLow && !targetLow) {
                setLowFlag(identity, isMinutes, false)
            }
        }

        private suspend fun setCriticalFlag(
            identity: String,
            isMinutes: Boolean,
            value: Boolean,
        ) {
            if (isMinutes) {
                deps.simNotificationFlagsRepository.setNotifiedCriticalMinutes(identity, value)
            } else {
                deps.simNotificationFlagsRepository.setNotifiedCriticalTraffic(identity, value)
            }
        }

        private suspend fun setLowFlag(
            identity: String,
            isMinutes: Boolean,
            value: Boolean,
        ) {
            if (isMinutes) {
                deps.simNotificationFlagsRepository.setNotifiedLowMinutes(identity, value)
            } else {
                deps.simNotificationFlagsRepository.setNotifiedLowTraffic(identity, value)
            }
        }

        private fun sendUsageNotificationForLevel(
            entity: SemanticEntity,
            daysRem: Int,
            autoReset: Boolean,
            packageType: PackageType,
            levelStatus: UsageStatus,
        ): Boolean {
            val appLocaleContext = AppLocaleContexts.forAppLocale(applicationContext)
            val levelRes =
                if (levelStatus == UsageStatus.CRITICAL) {
                    R.string.usage_level_critical
                } else {
                    R.string.usage_level_low
                }
            val level = appLocaleContext.getString(levelRes)
            val isMinutes = packageType == PackageType.MINUTES

            val body =
                if (isMinutes) {
                    val remText = FormatUtils.formatMinutesPlural(appLocaleContext, entity.remaining.toInt())
                    val remainingPercent = ((entity.remaining * HUNDRED) / entity.limit).toInt()
                    formatLowMinutesBody(appLocaleContext, autoReset, level, remText, remainingPercent, daysRem)
                } else {
                    val remainingGb = (entity.remaining.toDouble() / Constants.BYTES_PER_GB).toFloat()
                    val remainingPercent = ((entity.remaining * HUNDRED) / entity.limit).toInt()
                    formatLowTrafficBody(appLocaleContext, autoReset, level, remainingGb, remainingPercent, daysRem)
                }

            val notificationType = if (isMinutes) UsageNotificationType.MINUTES else UsageNotificationType.TRAFFIC
            return sendNotificationWithBody(entity.operatorName, body, entity.simSlot, notificationType)
        }

        private fun getMinutesExhaustedResId(autoReset: Boolean): Int =
            if (autoReset) {
                R.string.notification_minutes_exhausted_body
            } else {
                R.string.notification_minutes_exhausted_body_no_period
            }

        private fun formatLowMinutesBody(
            appLocaleContext: Context,
            autoReset: Boolean,
            level: String,
            remText: String,
            remPct: Int,
            daysRem: Int,
        ): String =
            if (autoReset) {
                val daysText = FormatUtils.formatDaysPlural(appLocaleContext, daysRem)
                appLocaleContext.getString(
                    R.string.notification_minutes_body,
                    level,
                    remText,
                    remPct,
                    daysText,
                )
            } else {
                appLocaleContext.getString(
                    R.string.notification_minutes_body_no_period,
                    level,
                    remText,
                    remPct,
                )
            }

        private fun sendNotificationWithBodyRes(
            operator: String,
            bodyResId: Int,
            slot: Int,
            type: UsageNotificationType,
        ): Boolean {
            val appLocaleContext =
                AppLocaleContexts
                    .forAppLocale(applicationContext)
            val body = appLocaleContext.getString(bodyResId)
            return sendNotificationWithBody(operator, body, slot, type)
        }

        private fun sendNotificationWithBody(
            operator: String,
            body: String,
            slot: Int,
            type: UsageNotificationType,
        ): Boolean {
            val appLocaleContext =
                AppLocaleContexts
                    .forAppLocale(applicationContext)
            val titleRes =
                if (type == UsageNotificationType.MINUTES) {
                    R.string.notification_minutes_title
                } else {
                    R.string.notification_traffic_title
                }
            val title = appLocaleContext.getString(titleRes, operator)
            return deps.notificationHelper.sendUsageNotification(title, body, slot, type)
        }

        private fun getTrafficExhaustedResId(autoReset: Boolean): Int =
            if (autoReset) {
                R.string.notification_traffic_exhausted_body
            } else {
                R.string.notification_traffic_exhausted_body_no_period
            }

        private fun formatLowTrafficBody(
            appLocaleContext: Context,
            autoReset: Boolean,
            level: String,
            remGb: Float,
            remPct: Int,
            daysRem: Int,
        ): String =
            if (autoReset) {
                val daysText = FormatUtils.formatDaysPlural(appLocaleContext, daysRem)
                appLocaleContext.getString(
                    R.string.notification_traffic_body,
                    level,
                    remGb,
                    remPct,
                    daysText,
                )
            } else {
                appLocaleContext.getString(
                    R.string.notification_traffic_body_no_period,
                    level,
                    remGb,
                    remPct,
                )
            }

        companion object {
            private const val HUNDRED = 100

            /**
             * Overall worker execution timeout (upper bound: 120 seconds).
             * Result on trigger: [Result.retry].
             */
            private const val WORKER_TIMEOUT_MS = 120_000L

            /**
             * Worker refresh operation timeout (upper bound: 30 seconds).
             * Result on trigger: [Result.retry] or fallback.
             */
            private const val WORKER_REFRESH_TIMEOUT_MS = 30_000L
        }
    }
