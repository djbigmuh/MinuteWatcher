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
import java.io.IOException
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

                if (!permissionChecker.checkPermissions(_status)) return

                val activeSims = fetchActiveSims()
                handleActiveSims(activeSims)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                errorHandler.handleUpdateError(e, _status)
            } catch (e: SecurityException) {
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
                baselineManager.cleanupRetries(emptySet())
                trafficMutex.withLock {
                    _status.value = TrackingStatus.Idle
                }
                return
            }

            val activeIdentities = activeSims.asSequence().map { it.identity.stableId }.toSet()
            baselineManager.cleanupRetries(activeIdentities)

            try {
                processActiveSimsTraffic(activeSims)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                AppLogger.e("TrafficTracker: handleActiveSims storage error", e)
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR, e.message)
            } catch (e: SecurityException) {
                AppLogger.e("TrafficTracker: handleActiveSims permission error", e)
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING, e.message)
            }
        }

        private suspend fun processActiveSimsTraffic(activeSims: List<ActiveSimInfo>) {
            try {
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                AppLogger.e("TrafficTracker: processActiveSimsTraffic storage error", e)
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR, e.message)
            } catch (e: SecurityException) {
                AppLogger.e("TrafficTracker: processActiveSimsTraffic permission error", e)
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING, e.message)
            }
        }

    @Suppress("ReturnCount", "LongMethod", "ComplexMethod")
    private suspend fun performTrafficUpdate(
        target: TargetInfo,
        targetIdentity: String,
    ) {
        withTimeoutOrNull(Constants.Flow.TRAFFIC_UPDATE_TOTAL_TIMEOUT_MS.milliseconds) {
            var phase = "initialization"
            try {
                // Phase 1: Read state under mutex
                val (startTime, initialRetryCount) =
                    trafficMutex.withLock {
                        phase = "usage fetch"
                        val usage =
                            simUsageRepository
                                .getSimUsage(targetIdentity)
                                .firstWithTimeout(Constants.Flow.LONG_TIMEOUT_MS.milliseconds)
                                ?: run {
                                    errorHandler.handleUsageReadTimeout(targetIdentity, _status)
                                    return@withTimeoutOrNull
                                }

                        phase = "identity check"
                        val activeIdentities =
                            simIdentityRepository
                                .getActiveIdentities()
                                .firstWithTimeout(Constants.Flow.LONG_TIMEOUT_MS.milliseconds)
                                ?: return@withTimeoutOrNull

                        if (targetIdentity !in activeIdentities) {
                            AppLogger.d("TrafficTracker: $targetIdentity no longer active, skipping")
                            return@withTimeoutOrNull
                        }

                        phase = "retry check"
                        val retryCount = baselineManager.baselineNeedsRetry[targetIdentity]
                        if ((retryCount != null) &&
                            (retryCount >= TrafficBaselineManager.MAX_BASELINE_RETRIES)
                        ) {
                            baselineManager.baselineNeedsRetry.remove(targetIdentity)
                            errorHandler.handleBaselineRetryFailure(targetIdentity, _status)
                            return@withTimeoutOrNull
                        }

                        val st = if (retryCount != null) 0L else usage.lastResetDate
                        st to retryCount
                    }

                // Phase 2: System query OUTSIDE mutex
                phase = "stats query"
                val statsResult = baselineManager.getCurrentMobileBytes(startTime)

                // Phase 3: Verify and write under mutex
                trafficMutex.withLock {
                    phase = "state verification"
                    val currentActive =
                        simIdentityRepository
                            .getActiveIdentities()
                            .firstWithTimeout(Constants.Flow.LONG_TIMEOUT_MS.milliseconds)
                            ?: return@withLock
                    if (targetIdentity !in currentActive) {
                        AppLogger.d("TrafficTracker: $targetIdentity deactivated during query, skipping")
                        return@withLock
                    }

                    val currentRetryCount = baselineManager.baselineNeedsRetry[targetIdentity]
                    if (currentRetryCount != initialRetryCount) {
                        AppLogger.d("TrafficTracker: retry state changed during query for $targetIdentity, skipping")
                        return@withLock
                    }

                    // Handle results
                    if (initialRetryCount != null) {
                        phase = "retry action"
                        handleRetryAction(target, targetIdentity, initialRetryCount, statsResult)
                    } else {
                        if (statsResult !is StatsResult.Success) {
                            errorHandler.handleStatsFailure(statsResult, _status)
                            return@withLock
                        }

                        phase = "baseline fetch"
                        val lastTotalBytes = fetchLastTotalBytes(targetIdentity, statsResult)
                        if (lastTotalBytes == null) {
                            errorHandler.handleLastTotalBytesTimeout(targetIdentity, _status)
                            return@withLock
                        }

                        phase = "atomic update"
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
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                AppLogger.e(
                    "TrafficTracker: Storage error during traffic update at phase $phase for $targetIdentity",
                    e,
                )
                _status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.DATABASE_ERROR,
                        "Traffic update storage failure at $phase: ${e.message}",
                    )
            } catch (e: SecurityException) {
                AppLogger.e(
                    "TrafficTracker: Permission error during traffic update at phase $phase for $targetIdentity",
                    e,
                )
                _status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.PERMISSION_MISSING,
                        "Traffic update permission failure at $phase: ${e.message}",
                    )
            }
        } ?: run {
            // This run block is for the case when withTimeoutOrNull returns null
            // We should NOT be under the lock here if we want to avoid blocking.
            // But the phase might still be useful.
            AppLogger.e("TrafficTracker: Traffic update timed out for $targetIdentity")
            _status.value =
                TrackingStatus.Error(
                    TrackingStatus.ErrorType.UNKNOWN,
                    "Traffic update timeout",
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
