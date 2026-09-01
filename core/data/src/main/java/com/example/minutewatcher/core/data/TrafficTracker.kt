package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.SimUsageResetRepository
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tracks mobile traffic usage for the device.
 * Attributes all mobile traffic to the CURRENT DEFAULT DATA SIM.
 *
 * This class acts as an orchestrator, delegating specific responsibilities to:
 * - [TrafficBaselineManager] for baseline management and retries.
 * - [TrafficDeltaCalculator] for delta computation and usage updates.
 * - [TrafficSimChangeHandler] for handling SIM card changes.
 */
@Singleton
@Suppress("LongParameterList")
class TrafficTracker
    @Inject
    constructor(
        private val simIdentityRepository: SimIdentityRepository,
        private val simUsageRepository: SimUsageRepository,
        private val simUsageResetRepository: SimUsageResetRepository,
        private val permissionManager: PermissionManager,
        private val baselineManager: TrafficBaselineManager,
        private val deltaCalculator: TrafficDeltaCalculator,
        private val simChangeHandler: TrafficSimChangeHandler,
        private val targetResolver: TrafficTargetResolver,
        private val errorHandler: TrafficErrorHandler,
        private val permissionChecker: TrafficPermissionChecker,
    ) : TrackingStatusProvider {
        override val key: String = "traffic"

        private val _status = MutableStateFlow<TrackingStatus>(TrackingStatus.Idle)
        override val status: StateFlow<TrackingStatus> = _status.asStateFlow()

        private val trafficMutex = Mutex()

        override suspend fun recover() {
            updateTrafficUsage()
        }

        suspend fun resetBaseline(identity: String) {
            if (identity.startsWith(Constants.SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("TrafficTracker: resetBaseline called with invalid identity: $identity")
                return
            }
            simChangeHandler.withSimChangeLock {
                trafficMutex.withLock {
                    if (!permissionChecker.checkPermissions(_status)) return@withLock
                    baselineManager.resetBaseline(identity, _status) {
                        val currentDataSubId =
                            try {
                                permissionManager.getDefaultDataSubscriptionId()
                            } catch (_: Exception) {
                                -1
                            }
                        simChangeHandler.updateLastDataSubId(currentDataSubId)
                    }
                }
            }
        }

        suspend fun updateTrafficUsage() {
            try {
                val currentStatus = _status.value
                val isTimeoutError =
                    (currentStatus is TrackingStatus.Error) &&
                        (currentStatus.type == TrackingStatus.ErrorType.UNKNOWN)
                if (isTimeoutError) {
                    _status.value = TrackingStatus.Idle
                }

                withTimeoutOrNull(Constants.Flow.TRAFFIC_UPDATE_TOTAL_TIMEOUT_MS.milliseconds) {
                    if (!permissionChecker.checkPermissions(_status)) return@withTimeoutOrNull

                    val activeSims = fetchActiveSims()
                    handleActiveSims(activeSims)
                } ?: run {
                    AppLogger.e("TrafficTracker: Traffic update timed out")
                    _status.value =
                        TrackingStatus.Error(
                            TrackingStatus.ErrorType.UNKNOWN,
                            "Traffic update timeout",
                        )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("TrafficTracker: Unexpected error during update", e)
                errorHandler.handleUpdateError(e, _status)
            }
        }

        private suspend fun fetchActiveSims(): List<ActiveSimInfo> =
            withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                simIdentityRepository.observeActiveSims().first()
            } ?: run {
                AppLogger.e("TrafficTracker: timeout fetching active sims")
                emptyList()
            }

        private suspend fun handleActiveSims(activeSims: List<ActiveSimInfo>) {
            if (activeSims.isEmpty()) {
                trafficMutex.withLock {
                    baselineManager.cleanupRetries(emptySet())
                    _status.value = TrackingStatus.Idle
                }
                return
            }

            val activeIdentities = activeSims.asSequence().map { it.identity.stableId }.toSet()
            trafficMutex.withLock {
                baselineManager.cleanupRetries(activeIdentities)
            }

            processActiveSimsTraffic(activeSims)
        }

        private suspend fun processActiveSimsTraffic(activeSims: List<ActiveSimInfo>) {
            val result = targetResolver.resolve(activeSims)

            if (result is TrafficTargetResolver.TargetResult.InconsistentFirmware) {
                baselineManager.cleanupRetries(emptySet())
                errorHandler.handleInconsistentFirmware(_status)
                return
            }

            if (result !is TrafficTargetResolver.TargetResult.Success) {
                AppLogger.w("TrafficTracker: resolution failed or SIMs inactive: $result")
                return
            }

            val target = result.target

            simChangeHandler.withSimChangeLock {
                val targetIdentity = simChangeHandler.handleSimChangeIfNeeded(target, _status)
                targetIdentity?.let { performTrafficUpdate(target, it) }
            }
        }

    private suspend fun performTrafficUpdate(
        target: TargetInfo,
        targetIdentity: String,
    ) {
        // 1. Чтение начальных данных вне лока (для определения startTime)
        val initialUsage =
            simUsageRepository
                .getSimUsage(targetIdentity)
                .firstWithTimeout(Constants.Flow.LONG_TIMEOUT_MS.milliseconds)
                ?: run {
                    errorHandler.handleUsageReadTimeout(targetIdentity, _status)
                    return
                }

        // 2. Тяжёлый системный вызов вне лока
        //    initialRetryCount фиксируется до системного вызова и используется только для детекции устаревания результата внутри мьютекса.
        val initialRetryCount = baselineManager.baselineNeedsRetry[targetIdentity]
        val startTime = if (initialRetryCount != null) 0L else initialUsage.lastResetDate
        val statsResult = baselineManager.getCurrentMobileBytes(startTime)

        // 3. Вся остальная логика — внутри мьютекса
        trafficMutex.withLock {
            val currentActive =
                simIdentityRepository
                    .getActiveIdentities()
                    .firstWithTimeout(Constants.Flow.LONG_TIMEOUT_MS.milliseconds)
                    ?: return@withLock

            if (targetIdentity !in currentActive) {
                AppLogger.d("TrafficTracker: $targetIdentity no longer active, skipping")
                return@withLock
            }

            val currentUsage =
                simUsageRepository
                    .getSimUsage(targetIdentity)
                    .firstWithTimeout(Constants.Flow.LONG_TIMEOUT_MS.milliseconds)
                    ?: return@withLock

            val currentRetryCount = baselineManager.baselineNeedsRetry[targetIdentity]

            // Verify consistency: if reset date or retry state changed, the statsResult is stale
            if ((currentUsage.lastResetDate != initialUsage.lastResetDate) ||
                (currentRetryCount != initialRetryCount)
            ) {
                AppLogger.d("TrafficTracker: state changed during stats query for $targetIdentity, skipping")
                return@withLock
            }

            // Check retry limit
            if (initialRetryCount != null &&
                initialRetryCount >= TrafficBaselineManager.MAX_BASELINE_RETRIES
            ) {
                baselineManager.baselineNeedsRetry.remove(targetIdentity)
                errorHandler.handleBaselineRetryFailure(targetIdentity, _status)
                return@withLock
            }

            // Handle results
            if (initialRetryCount != null) {
                handleRetryAction(target, targetIdentity, initialRetryCount, statsResult)
            } else {
                if (statsResult !is StatsResult.Success) {
                    errorHandler.handleStatsFailure(statsResult, _status)
                    return@withLock
                }

                val lastTotalBytes = fetchLastTotalBytes(targetIdentity, statsResult)
                if (lastTotalBytes == null) {
                    errorHandler.handleLastTotalBytesTimeout(targetIdentity, _status)
                    return@withLock
                }

                val writeSuccess =
                    withTimeoutOrNull(Constants.Flow.LONG_TIMEOUT_MS.milliseconds) {
                        deltaCalculator.updateTrafficBaselineAndUsage(
                            targetIdentity,
                            statsResult.bytes,
                            lastTotalBytes,
                            target,
                        )
                        simChangeHandler.updateLastDataSubId(target.dataSubId)
                        true
                    } ?: false

                if (!writeSuccess) {
                    AppLogger.e("TrafficTracker: Traffic update write timed out for $targetIdentity")
                    _status.value =
                        TrackingStatus.Error(
                            TrackingStatus.ErrorType.UNKNOWN,
                            "Traffic update write timeout",
                        )
                    return@withLock
                }
                _status.value = TrackingStatus.Active
            }
        }
    }

        private suspend fun handleRetryAction(
            target: TargetInfo,
            targetIdentity: String,
            currentPending: Int,
            statsResult: StatsResult,
        ) {
            if (statsResult is StatsResult.Success) {
                withContext(NonCancellable) {
                    simUsageResetRepository.setLastTotalBytes(targetIdentity, statsResult.bytes)
                    simChangeHandler.updateLastDataSubId(target.dataSubId)
                }
                baselineManager.baselineNeedsRetry.remove(targetIdentity)
                _status.value = TrackingStatus.Active
                AppLogger.d("Traffic baseline reset on retry for $targetIdentity to ${statsResult.bytes}")
            } else {
                baselineManager.baselineNeedsRetry[targetIdentity] = currentPending + 1
                val reason =
                    if (statsResult is StatsResult.Error) {
                        statsResult.exception.message
                    } else {
                        "Stats unavailable"
                    }
                _status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.UNKNOWN,
                        reason ?: "Stats unavailable, retry pending",
                    )
            }
        }

        private suspend fun fetchLastTotalBytes(
            targetIdentity: String,
            statsResult: StatsResult,
        ): Long? {
            if (statsResult !is StatsResult.Success) return null
            return simUsageResetRepository
                .getLastTotalBytes(targetIdentity)
                .firstWithTimeout(Constants.Flow.LONG_TIMEOUT_MS.milliseconds)
        }
    }
