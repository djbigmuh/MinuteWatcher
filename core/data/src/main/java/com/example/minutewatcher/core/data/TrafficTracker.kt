package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.domain.InvalidSlotException
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimNotFoundException
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.SimUsageResetRepository
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

internal class TrafficUpdateTimeoutException(message: String = MESSAGE) : Exception(message) {
    companion object {
        const val MESSAGE = "Traffic update timeout"
    }
}

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
        private val permissionStateNotifier: PermissionStateNotifier,
        @ApplicationScope private val scope: CoroutineScope,
    ) : TrackingStatusProvider {
    override val key: String = "traffic"

    private val _status = MutableStateFlow<TrackingStatus>(TrackingStatus.Idle)
    override val status: StateFlow<TrackingStatus> = _status.asStateFlow()

    private val trafficMutex = Mutex()
    private val isInitialized = AtomicBoolean(false)
    private val initializationFailed = AtomicBoolean(false)

    init {
        if (isInitialized.compareAndSet(false, true)) {
            scope.launch {
                initializeWithRetry()
            }
            startAutomaticRecoveryObservers()
        }
    }

    private fun startAutomaticRecoveryObservers() {
        scope.launch {
            try {
                permissionStateNotifier.changes.collect {
                    if (permissionManager.hasPhoneStatePermission() &&
                        permissionManager.hasUsageStatsPermission() &&
                        initializationFailed.compareAndSet(true, false)
                    ) {
                        AppLogger.d("TrafficTracker: attempting auto-recovery after permission change")
                        initializeWithRetry()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("TrafficTracker: Permission observer failed", e)
            }
        }
        scope.launch {
            try {
                simIdentityRepository.observeActiveSims().collect {
                    if (initializationFailed.compareAndSet(true, false)) {
                        AppLogger.d("TrafficTracker: attempting auto-recovery after SIM change")
                        initializeWithRetry()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("TrafficTracker: SIM observer failed", e)
            }
        }
    }

    private suspend fun initializeWithRetry() {
        for (attempt in 1..MAX_INIT_ATTEMPTS) {
            try {
                updateTrafficUsageLogic()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isTerminalInitError(e)) {
                    AppLogger.e("TrafficTracker: terminal init error", e)
                    initializationFailed.set(true)
                    return
                }
                if (attempt >= MAX_INIT_ATTEMPTS) break

                val delayMs = INIT_RETRY_BASE_MS * (1 shl (attempt - 1))
                AppLogger.w("TrafficTracker: init attempt $attempt failed, retry in ${delayMs}ms", e)
                delay(delayMs.milliseconds)
            }
        }
        initializationFailed.set(true)
    }

    private fun isTerminalInitError(e: Exception): Boolean =
        (e is SecurityException) || (e is TrafficUpdateTimeoutException)

    override suspend fun recover() {
        if (initializationFailed.compareAndSet(true, false)) {
            AppLogger.d("TrafficTracker: attempting manual recovery")
            initializeWithRetry()
        } else {
            // Already initialized or in progress, but we can trigger an update anyway
            updateTrafficUsage()
        }
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
            updateTrafficUsageLogic()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Handled in updateTrafficUsageLogic (status set, error logged)
        }
    }

    private suspend fun updateTrafficUsageLogic() {
        try {
            val currentStatus = _status.value
            val isTimeoutError =
                (currentStatus is TrackingStatus.Error) &&
                    (currentStatus.type == TrackingStatus.ErrorType.UNKNOWN) &&
                    (currentStatus.message == TrafficUpdateTimeoutException.MESSAGE)
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
                        TrafficUpdateTimeoutException.MESSAGE,
                    )
                throw TrafficUpdateTimeoutException()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            AppLogger.e("TrafficTracker: SecurityException during update", e)
            _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING, e.message)
            throw e
        } catch (e: Exception) {
            if (e !is TrafficUpdateTimeoutException) {
                AppLogger.e("TrafficTracker: Unexpected error during update", e)
                errorHandler.handleUpdateError(e, _status)
            }
            throw e
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

        try {
            simChangeHandler.withSimChangeLock {
                val targetIdentity = simChangeHandler.handleSimChangeIfNeeded(target, _status)
                targetIdentity?.let { performTrafficUpdate(target, it) }
            }
        } catch (e: InvalidSlotException) {
            AppLogger.w("TrafficTracker: InvalidSlotException for slot ${target.slot}", e)
            _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
        } catch (e: SimNotFoundException) {
            AppLogger.w("TrafficTracker: SimNotFoundException for slot ${target.slot}", e)
            _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
        }
    }

    private suspend fun performTrafficUpdate(
        target: TargetInfo,
        targetIdentity: String,
    ) {
        trafficMutex.withLock {
            val currentActive =
                simIdentityRepository
                    .getActiveIdentities()
                    .firstWithTimeout(Constants.Flow.LONG_TIMEOUT_MS.milliseconds)
                    ?: return@withLock

            if (targetIdentity !in currentActive) {
                AppLogger.d("TrafficTracker: $targetIdentity no longer active, skipping")
                _status.value = TrackingStatus.Idle
                return@withLock
            }

            val currentUsage =
                simUsageRepository
                    .getSimUsage(targetIdentity)
                    .firstWithTimeout(Constants.Flow.LONG_TIMEOUT_MS.milliseconds)
                    ?: run {
                        errorHandler.handleUsageReadTimeout(targetIdentity, _status)
                        return@withLock
                    }

            val currentRetryCount = baselineManager.baselineNeedsRetry[targetIdentity]
            val actualStartTime = if (currentRetryCount != null) 0L else currentUsage.lastResetDate
            val statsResult = baselineManager.getCurrentMobileBytes(actualStartTime)

            // Check retry limit
            if ((currentRetryCount != null) &&
                (currentRetryCount >= TrafficBaselineManager.MAX_BASELINE_RETRIES)
            ) {
                baselineManager.baselineNeedsRetry.remove(targetIdentity)
                errorHandler.handleBaselineRetryFailure(targetIdentity, _status)
                return@withLock
            }

            // Handle results
            val finalStats = statsResult
            if (currentRetryCount != null) {
                handleRetryAction(target, targetIdentity, currentRetryCount, finalStats)
            } else {
                if (finalStats !is StatsResult.Success) {
                    errorHandler.handleStatsFailure(finalStats, _status)
                    return@withLock
                }

                val lastTotalBytes = fetchLastTotalBytes(targetIdentity, finalStats)
                if (lastTotalBytes == null) {
                    errorHandler.handleLastTotalBytesTimeout(targetIdentity, _status)
                    return@withLock
                }

                val writeSuccess =
                    withTimeoutOrNull(Constants.Flow.LONG_TIMEOUT_MS.milliseconds) {
                        deltaCalculator.updateTrafficBaselineAndUsage(
                            targetIdentity,
                            finalStats.bytes,
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
            val nextRetry = currentPending + 1
            if (nextRetry >= TrafficBaselineManager.MAX_BASELINE_RETRIES) {
                baselineManager.baselineNeedsRetry.remove(targetIdentity)
                errorHandler.handleBaselineRetryFailure(targetIdentity, _status)
            } else {
                baselineManager.baselineNeedsRetry[targetIdentity] = nextRetry
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

    private companion object {
        private const val MAX_INIT_ATTEMPTS = 3
        private const val INIT_RETRY_BASE_MS = 1000L
    }
}
