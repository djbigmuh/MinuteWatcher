package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.catchingCancellation
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.SimUsageResetRepository
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.TrackingStatus
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
            catchingCancellation(
                block = {
                    val currentStatus = _status.value
                    val isTimeoutError =
                        (currentStatus is TrackingStatus.Error) &&
                            (currentStatus.type == TrackingStatus.ErrorType.UNKNOWN)
                    if (isTimeoutError) {
                        _status.value = TrackingStatus.Idle
                    }

                    if (!permissionChecker.checkPermissions(_status)) return@catchingCancellation

                    val activeSims = fetchActiveSims()
                    handleActiveSims(activeSims)
                },
            ) { e -> errorHandler.handleUpdateError(e, _status) }
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
                baselineManager.cleanupRetries(emptySet())
                trafficMutex.withLock {
                    _status.value = TrackingStatus.Idle
                }
                return
            }

            val activeIdentities = activeSims.asSequence().map { it.identity.stableId }.toSet()
            baselineManager.cleanupRetries(activeIdentities)

            processActiveSimsTraffic(activeSims)
        }

        private suspend fun processActiveSimsTraffic(activeSims: List<ActiveSimInfo>) {
            val result = targetResolver.resolve(activeSims)

            if (result is TrafficTargetResolver.TargetResult.InconsistentFirmware) {
                baselineManager.cleanupRetries(emptySet())
                errorHandler.handleInconsistentFirmware(_status)
                return
            }

            val target = (result as TrafficTargetResolver.TargetResult.Success).target

            simChangeHandler.withSimChangeLock {
                val targetIdentity = simChangeHandler.handleSimChangeIfNeeded(target, _status)
                targetIdentity?.let { performTrafficUpdate(target, it) }
            }
        }

    @Suppress("ReturnCount", "LongMethod")
    private suspend fun performTrafficUpdate(
        target: TargetInfo,
        targetIdentity: String,
    ) {
        val updateResult =
            withTimeoutOrNull(Constants.Flow.TRAFFIC_UPDATE_TOTAL_TIMEOUT_MS.milliseconds) {
                // Fetch Usage upfront to keep the critical section (trafficMutex) as short as possible.
                val usage =
                    simUsageRepository
                        .getSimUsage(targetIdentity)
                        .firstWithTimeout(Constants.Flow.LONG_TIMEOUT_MS.milliseconds)
                        ?: run {
                            trafficMutex.withLock {
                                errorHandler.handleUsageReadTimeout(targetIdentity, _status)
                            }
                            return@withTimeoutOrNull false
                        }

                trafficMutex.withLock {
                    AppLogger.d("TrafficTracker: Update phase: Mutex acquired for $targetIdentity")

                    // Check if SIM is still active under the lock to prevent race conditions.
                    val activeIdentities =
                        simIdentityRepository
                            .getActiveIdentities()
                            .firstWithTimeout(Constants.Flow.LONG_TIMEOUT_MS.milliseconds)
                            ?: return@withLock false
                    if (targetIdentity !in activeIdentities) {
                        AppLogger.d("TrafficTracker: $targetIdentity no longer active, skipping")
                        return@withLock false
                    }

                    val initialRetryCount = baselineManager.baselineNeedsRetry[targetIdentity]

                    // Check for exceeded retries (Check again inside lock)
                    if (initialRetryCount != null &&
                        initialRetryCount >= TrafficBaselineManager.MAX_BASELINE_RETRIES
                    ) {
                        baselineManager.baselineNeedsRetry.remove(targetIdentity)
                        errorHandler.handleBaselineRetryFailure(targetIdentity, _status)
                        return@withLock false
                    }

                    // Path A: Current state is Retry
                    if (initialRetryCount != null) {
                        val statsResult = baselineManager.getCurrentMobileBytes(0L)
                        handleRetryAction(target, targetIdentity, initialRetryCount, statsResult)
                        return@withLock true
                    }

                    // Path B: Normal path
                    val startTime = usage.lastResetDate
                    val statsResult = baselineManager.getCurrentMobileBytes(startTime)

                    if (statsResult !is StatsResult.Success) {
                        errorHandler.handleStatsFailure(statsResult, _status)
                        return@withLock false
                    }

                    val lastTotalBytes = fetchLastTotalBytes(targetIdentity, statsResult)

                    if (lastTotalBytes == null) {
                        errorHandler.handleLastTotalBytesTimeout(targetIdentity, _status)
                        return@withLock false
                    }

                    // Perform atomic update
                    withContext(NonCancellable) {
                        deltaCalculator.updateTrafficBaselineAndUsage(
                            targetIdentity,
                            statsResult.bytes,
                            lastTotalBytes,
                            target,
                        )
                        simChangeHandler.updateLastDataSubId(target.dataSubId)
                    }
                    _status.value = TrackingStatus.Active
                    true
                }
            }

        if (updateResult == null) {
            val phase = if (trafficMutex.isLocked) "execution" else "mutex acquisition or data fetch"
            AppLogger.e("TrafficTracker: Traffic update timed out during $phase for $targetIdentity")
            _status.value =
                TrackingStatus.Error(
                    TrackingStatus.ErrorType.UNKNOWN,
                    "Traffic update timeout ($phase)",
                )
        }
    }

    private suspend fun handleRetryAction(
        target: TargetInfo,
        targetIdentity: String,
        currentPending: Int,
        statsResult: StatsResult,
    ) {
        if (statsResult is StatsResult.Success) {
            simUsageResetRepository.setLastTotalBytes(targetIdentity, statsResult.bytes)
            simChangeHandler.updateLastDataSubId(target.dataSubId)
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
