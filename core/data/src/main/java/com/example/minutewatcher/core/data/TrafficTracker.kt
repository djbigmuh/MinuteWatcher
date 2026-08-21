package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.SimUsageResetRepository
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CancellationException
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
    ) : TrackingStatusProvider {
        override val key: String = "traffic"

        private val _status = MutableStateFlow<TrackingStatus>(TrackingStatus.Idle)
        override val status: StateFlow<TrackingStatus> = _status.asStateFlow()

        private val trafficMutex = Mutex()

        override suspend fun recover() {
            updateTrafficUsage()
        }

        suspend fun resetBaseline(identity: String) {
            trafficMutex.withLock {
                if (identity.startsWith(Constants.SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                    AppLogger.w("TrafficTracker: resetBaseline called with invalid identity: $identity")
                    return@withLock
                }
                simChangeHandler.withSimChangeLock {
                    if (!checkPermissions()) return@withSimChangeLock
                    baselineManager.resetBaseline(identity, _status) {
                        val currentDataSubId =
                            try {
                                permissionManager.getDefaultDataSubscriptionId()
                            } catch (ignored: Exception) {
                                -1
                            }
                        simChangeHandler.updateLastDataSubId(currentDataSubId)
                    }
                }
            }
        }

        suspend fun updateTrafficUsage() {
            trafficMutex.withLock {
                try {
                    if (!checkPermissions()) return

                    var activeSims =
                        withTimeoutOrNull(Constants.Flow.LONG_TIMEOUT_MS.milliseconds) {
                            simIdentityRepository.observeActiveSims().first()
                        }

                    if (activeSims == null) {
                        AppLogger.w("TrafficTracker: observeActiveSims timed out, retrying once...")
                        activeSims =
                            withTimeoutOrNull(Constants.Flow.LONG_TIMEOUT_MS.milliseconds) {
                                simIdentityRepository.observeActiveSims().first()
                            }
                    }

                    if (activeSims == null) {
                        AppLogger.e("TrafficTracker: observeActiveSims timed out after retry")
                        _status.value =
                            TrackingStatus.Error(
                                TrackingStatus.ErrorType.UNKNOWN,
                                "SIM list read timeout",
                            )
                        return
                    }
                    if (activeSims.isEmpty()) {
                        baselineManager.cleanupRetries(emptySet())
                        _status.value = TrackingStatus.Idle
                        return
                    }

                    val activeIdentities = activeSims.asSequence().map { it.identity.stableId }.toSet()
                    baselineManager.cleanupRetries(activeIdentities)

                    processActiveSimsTraffic(activeSims)
                } catch (e: SecurityException) {
                    AppLogger.e("SecurityException in TrafficTracker", e)
                    _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING, e.message)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Error updating traffic", e)
                    _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
                }
            }
        }

        private suspend fun processActiveSimsTraffic(activeSims: List<ActiveSimInfo>) {
            val result = targetResolver.resolve(activeSims)

            if (result is TrafficTargetResolver.TargetResult.InconsistentFirmware) {
                AppLogger.w("TrafficTracker: inconsistent firmware state")
                baselineManager.cleanupRetries(emptySet())
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION)
                return
            }

            val target = (result as TrafficTargetResolver.TargetResult.Success).target

            simChangeHandler.withSimChangeLock {
                simChangeHandler.handleSimChangeIfNeeded(target, _status) { targetIdentity ->
                    performTrafficUpdate(target, targetIdentity)
                }
            }
        }

        private suspend fun performTrafficUpdate(
            target: TargetInfo,
            targetIdentity: String,
        ) {
            val pendingRetry = baselineManager.baselineNeedsRetry[targetIdentity]
            if (pendingRetry != null) {
                if (pendingRetry >= TrafficBaselineManager.MAX_BASELINE_RETRIES) {
                    AppLogger.e("TrafficTracker: baseline reset for $targetIdentity failed after all retries")
                    baselineManager.baselineNeedsRetry.remove(targetIdentity)
                    _status.value =
                        TrackingStatus.Error(
                            TrackingStatus.ErrorType.DATABASE_ERROR,
                            "Baseline reset failed for $targetIdentity",
                        )
                    return
                }
                val statsResult = baselineManager.getCurrentMobileBytes(0L)
                if (statsResult is StatsResult.Success) {
                    simUsageResetRepository.setLastTotalBytes(targetIdentity, statsResult.bytes)
                    baselineManager.baselineNeedsRetry.remove(targetIdentity)
                    _status.value = TrackingStatus.Active
                    return
                }
                baselineManager.baselineNeedsRetry[targetIdentity] = pendingRetry + 1
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
                AppLogger.e("TrafficTracker: getSimUsage timed out for $targetIdentity")
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, "Timeout reading simUsage")
                return
            }

            val statsResult = baselineManager.getCurrentMobileBytes(usage.lastResetDate)
            if (baselineManager.handleBaselineRetry(target, targetIdentity, statsResult, _status) {
                    simChangeHandler.updateLastDataSubId(it)
                }
            ) {
                return
            }

            processStatsResult(target, targetIdentity, statsResult)
        }

        private suspend fun processStatsResult(
            target: TargetInfo,
            targetIdentity: String,
            statsResult: StatsResult,
        ) {
            val lastTotalBytes =
                if (statsResult is StatsResult.Success) {
                    withTimeoutOrNull(Constants.Flow.LONG_TIMEOUT_MS.milliseconds) {
                        simUsageResetRepository.getLastTotalBytes(targetIdentity).first()
                    }
                } else {
                    -1L
                }

            if (lastTotalBytes == null) {
                AppLogger.e("TrafficTracker: getLastTotalBytes timed out for $targetIdentity")
                _status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.UNKNOWN,
                        "Timeout reading lastTotalBytes",
                    )
            } else {
                executeTrafficUpdate(target, targetIdentity, statsResult, lastTotalBytes)
            }
        }

        private suspend fun executeTrafficUpdate(
            target: TargetInfo,
            targetIdentity: String,
            statsResult: StatsResult,
            lastTotalBytes: Long,
        ) {
            if (statsResult !is StatsResult.Success) {
                val message = if (statsResult is StatsResult.Error) statsResult.exception.message else "Stats unavailable"
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, message)
                return
            }

            deltaCalculator.updateTrafficBaselineAndUsage(targetIdentity, statsResult.bytes, lastTotalBytes, target)
            simChangeHandler.updateLastDataSubId(target.dataSubId)
            _status.value = TrackingStatus.Active
        }

        private fun checkPermissions(): Boolean {
            val error =
                when {
                    !permissionManager.isTelephonySupported() ->
                        TrackingStatus.Error(
                            TrackingStatus.ErrorType.UNKNOWN,
                            "SubscriptionManager unavailable",
                        )
                    !permissionManager.hasUsageStatsPermission() ->
                        TrackingStatus.Error(
                            TrackingStatus.ErrorType.PERMISSION_MISSING,
                            "Usage Access missing",
                        )
                    !permissionManager.hasPhoneStatePermission() ->
                        TrackingStatus.Error(
                            TrackingStatus.ErrorType.PERMISSION_MISSING,
                            "Phone State missing",
                        )
                    else -> null
                }
            error?.let { _status.value = it }
            return error == null
        }
    }
