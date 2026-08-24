package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.catchingCancellation
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.SimUsageResetRepository
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
                    if (!permissionChecker.checkPermissions(_status)) return@catchingCancellation

                    val activeSims = fetchActiveSims() ?: return@catchingCancellation
                    handleActiveSims(activeSims)
                },
                onError = { e -> errorHandler.handleUpdateError(e, _status) },
            )
        }

        private suspend fun fetchActiveSims(): List<ActiveSimInfo>? {
            val flow = simIdentityRepository.observeActiveSims()
            val activeSims =
                withTimeoutOrNull(Constants.Flow.LONG_TIMEOUT_MS.milliseconds) {
                    flow.first()
                } ?: run {
                    AppLogger.w("TrafficTracker: observeActiveSims timed out, retrying once...")
                    withTimeoutOrNull(Constants.Flow.LONG_TIMEOUT_MS.milliseconds) {
                        flow.first()
                    }
                }

            if (activeSims == null) {
                errorHandler.handleSimListTimeout(_status)
            }
            return activeSims
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

            val target = (result as TrafficTargetResolver.TargetResult.Success).target

            simChangeHandler.withSimChangeLock {
                simChangeHandler.handleSimChangeIfNeeded(target, _status) { targetIdentity ->
                    performTrafficUpdate(target, targetIdentity)
                }
            }
        }

        @Suppress("ReturnCount", "LongMethod")
        private suspend fun performTrafficUpdate(
            target: TargetInfo,
            targetIdentity: String,
        ) {
            val pendingRetry = baselineManager.baselineNeedsRetry[targetIdentity]
            if (pendingRetry != null) {
                if (pendingRetry >= TrafficBaselineManager.MAX_BASELINE_RETRIES) {
                    trafficMutex.withLock {
                        baselineManager.baselineNeedsRetry.remove(targetIdentity)
                        errorHandler.handleBaselineRetryFailure(targetIdentity, _status)
                    }
                    return
                }
                val statsResult = baselineManager.getCurrentMobileBytes(0L)
                trafficMutex.withLock {
                    if (statsResult is StatsResult.Success) {
                        simUsageResetRepository.setLastTotalBytes(targetIdentity, statsResult.bytes)
                        baselineManager.baselineNeedsRetry.remove(targetIdentity)
                        _status.value = TrackingStatus.Active
                        return@withLock
                    }
                    baselineManager.baselineNeedsRetry[targetIdentity] = pendingRetry + 1
                }
                return
            }

            val currentActiveIdentities =
                withTimeoutOrNull(Constants.Flow.LONG_TIMEOUT_MS.milliseconds) {
                    simIdentityRepository.getActiveIdentities().first()
                }
            if ((currentActiveIdentities != null) && (targetIdentity !in currentActiveIdentities)) {
                return
            }

            val usage =
                withTimeoutOrNull(Constants.Flow.LONG_TIMEOUT_MS.milliseconds) {
                    simUsageRepository.getSimUsage(targetIdentity).first()
                } ?: run {
                    trafficMutex.withLock {
                        errorHandler.handleUsageReadTimeout(targetIdentity, _status)
                    }
                    return
                }

            val statsResult = baselineManager.getCurrentMobileBytes(usage.lastResetDate)

            val lastTotalBytes =
                if (statsResult is StatsResult.Success) {
                    withTimeoutOrNull(Constants.Flow.LONG_TIMEOUT_MS.milliseconds) {
                        simUsageResetRepository.getLastTotalBytes(targetIdentity).first()
                    }
                } else {
                    -1L
                }

            trafficMutex.withLock {
                if (baselineManager.handleBaselineRetry(
                        target,
                        targetIdentity,
                        statsResult,
                        _status,
                    ) {
                        simChangeHandler.updateLastDataSubId(it)
                    }
                ) {
                    return@withLock
                }

                if (lastTotalBytes == null) {
                    errorHandler.handleLastTotalBytesTimeout(targetIdentity, _status)
                } else {
                    executeTrafficUpdate(target, targetIdentity, statsResult, lastTotalBytes)
                }
            }
        }

        private suspend fun executeTrafficUpdate(
            target: TargetInfo,
            targetIdentity: String,
            statsResult: StatsResult,
            lastTotalBytes: Long,
        ) {
            if (statsResult !is StatsResult.Success) {
                errorHandler.handleStatsFailure(statsResult, _status)
                return
            }

            deltaCalculator.updateTrafficBaselineAndUsage(targetIdentity, statsResult.bytes, lastTotalBytes, target)
            simChangeHandler.updateLastDataSubId(target.dataSubId)
            _status.value = TrackingStatus.Active
        }
    }
