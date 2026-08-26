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
import kotlinx.coroutines.delay
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
                        currentStatus is TrackingStatus.Error &&
                            currentStatus.type == TrackingStatus.ErrorType.UNKNOWN
                    if (isTimeoutError) {
                        _status.value = TrackingStatus.Idle
                    }

                    if (!permissionChecker.checkPermissions(_status)) return@catchingCancellation

                    val activeSims = fetchActiveSims() ?: return@catchingCancellation
                    handleActiveSims(activeSims)
                },
                onError = { e -> errorHandler.handleUpdateError(e, _status) },
            )
        }

        private suspend fun fetchActiveSims(): List<ActiveSimInfo>? {
            val flow = simIdentityRepository.observeActiveSims()
            var activeSims =
                withTimeoutOrNull(Constants.Flow.LONG_TIMEOUT_MS.milliseconds) {
                    flow.first()
                }

            if (activeSims == null) {
                AppLogger.w("TrafficTracker: observeActiveSims timed out, retrying once after 1s...")
                delay(1000.milliseconds)
                activeSims =
                    withTimeoutOrNull(Constants.Flow.LONG_TIMEOUT_MS.milliseconds * 2) {
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
            val initialDecision =
                trafficMutex.withLock {
                    determineInitialUpdateAction(targetIdentity)
                }

            when (initialDecision) {
                is UpdateAction.Abort -> return
                is UpdateAction.Retry -> {
                    performRetryPath(target, targetIdentity, initialDecision.currentCount)
                }
                UpdateAction.Normal -> {
                    performNormalPath(target, targetIdentity)
                }
            }
        }

        private fun determineInitialUpdateAction(targetIdentity: String): UpdateAction {
            val pending = baselineManager.baselineNeedsRetry[targetIdentity]
            return when {
                pending == null -> UpdateAction.Normal
                pending >= TrafficBaselineManager.MAX_BASELINE_RETRIES -> {
                    baselineManager.baselineNeedsRetry.remove(targetIdentity)
                    errorHandler.handleBaselineRetryFailure(targetIdentity, _status)
                    UpdateAction.Abort
                }
                else -> UpdateAction.Retry(pending)
            }
        }

        private suspend fun performNormalPath(
            target: TargetInfo,
            targetIdentity: String,
        ) {
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
            val lastTotalBytes = fetchLastTotalBytes(targetIdentity, statsResult)

            val finalAction =
                trafficMutex.withLock {
                    determineFinalUpdateAction(targetIdentity, statsResult, lastTotalBytes)
                }

            handleFinalAction(finalAction, target, targetIdentity, statsResult, lastTotalBytes)
        }

        private suspend fun fetchLastTotalBytes(
            targetIdentity: String,
            statsResult: StatsResult,
        ): Long? {
            if (statsResult !is StatsResult.Success) return -1L
            return withTimeoutOrNull(Constants.Flow.LONG_TIMEOUT_MS.milliseconds) {
                simUsageResetRepository.getLastTotalBytes(targetIdentity).first()
            }
        }

        private fun determineFinalUpdateAction(
            targetIdentity: String,
            statsResult: StatsResult,
            lastTotalBytes: Long?,
        ): UpdateAction =
            if (baselineManager.baselineNeedsRetry.containsKey(targetIdentity)) {
                if (statsResult is StatsResult.Success) {
                    UpdateAction.Retry(0)
                } else {
                    _status.value =
                        TrackingStatus.Error(
                            TrackingStatus.ErrorType.UNKNOWN,
                            "Stats unavailable, retry pending",
                        )
                    UpdateAction.Abort
                }
            } else if (lastTotalBytes == null) {
                errorHandler.handleLastTotalBytesTimeout(targetIdentity, _status)
                UpdateAction.Abort
            } else {
                UpdateAction.Normal
            }

        private suspend fun handleFinalAction(
            action: UpdateAction,
            target: TargetInfo,
            targetIdentity: String,
            statsResult: StatsResult,
            lastTotalBytes: Long?,
        ) {
            when (action) {
                UpdateAction.Abort -> return
                is UpdateAction.Retry -> {
                    simUsageResetRepository.setLastTotalBytes(
                        targetIdentity,
                        (statsResult as StatsResult.Success).bytes,
                    )
                    simChangeHandler.updateLastDataSubId(target.dataSubId)
                    trafficMutex.withLock {
                        baselineManager.baselineNeedsRetry.remove(targetIdentity)
                        _status.value = TrackingStatus.Active
                    }
                }
                UpdateAction.Normal -> {
                    executeTrafficUpdate(target, targetIdentity, statsResult, lastTotalBytes!!)
                }
            }
        }

        private suspend fun performRetryPath(
            target: TargetInfo,
            targetIdentity: String,
            currentPending: Int,
        ) {
            val statsResult = baselineManager.getCurrentMobileBytes(0L)
            if (statsResult is StatsResult.Success) {
                // Phase 2: Write (вне мьютекса)
                simUsageResetRepository.setLastTotalBytes(targetIdentity, statsResult.bytes)
                simChangeHandler.updateLastDataSubId(target.dataSubId)

                // Phase 3: Commit (под мьютексом)
                trafficMutex.withLock {
                    baselineManager.baselineNeedsRetry.remove(targetIdentity)
                    _status.value = TrackingStatus.Active
                }
            } else {
                // Phase 3: Commit (под мьютексом)
                trafficMutex.withLock {
                    val current = baselineManager.baselineNeedsRetry[targetIdentity] ?: currentPending
                    baselineManager.baselineNeedsRetry[targetIdentity] = current + 1
                    _status.value =
                        TrackingStatus.Error(
                            TrackingStatus.ErrorType.UNKNOWN,
                            "Stats unavailable, retry pending",
                        )
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
                trafficMutex.withLock {
                    errorHandler.handleStatsFailure(statsResult, _status)
                }
                return
            }

            // Phase 2: Write (вне мьютекса)
            deltaCalculator.updateTrafficBaselineAndUsage(targetIdentity, statsResult.bytes, lastTotalBytes, target)
            simChangeHandler.updateLastDataSubId(target.dataSubId)

            // Phase 3: Commit (под мьютексом)
            trafficMutex.withLock {
                _status.value = TrackingStatus.Active
            }
        }

        private sealed interface UpdateAction {
            object Abort : UpdateAction

            data class Retry(
                val currentCount: Int,
            ) : UpdateAction

            object Normal : UpdateAction
        }
    }
