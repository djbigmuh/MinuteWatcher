package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.InvalidSlotException
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimNotFoundException
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.SimUsageResetRepository
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.TrackingKey
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

internal class TrafficUpdateTimeoutException(
    message: String = MESSAGE,
) : Exception(message) {
    companion object {
        const val MESSAGE = "Traffic update timeout"
    }
}

/**
 * Tracks mobile traffic usage for the device.
 * Attributes all mobile traffic to the CURRENT DEFAULT DATA SIM.
 *
 * This class acts as an orchestrator, delegating specific responsibilities to:
 * - [TrafficBaselineManager] for persistent baseline management and retries.
 * - [TrafficDeltaCalculator] for delta computation and usage updates.
 * - [TrafficSimChangeHandler] for handling SIM card changes.
 *
 * **Contract:**
 * - A timeout when fetching active SIMs is treated as a transient error and does
 *   NOT affect current tracking state or retry counters.
 * - An empty list of active SIMs is treated as "no data to track", which triggers
 *   cleanup of stale retry states.
 *
 * NOTE: Suppressing TooManyFunctions as this class is the central orchestrator for traffic tracking,
 * delegating to specialized components while maintaining the primary tracking lifecycle.
 */
@Singleton
@Suppress("LongParameterList", "TooManyFunctions")
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
        private val dataChangeNotifier: DataChangeNotifier,
        @ApplicationScope private val scope: CoroutineScope,
    ) : TrackingStatusProvider {
    override val key: TrackingKey = TrackingKey.TRAFFIC

    private var _status = MutableStateFlow<TrackingStatus>(TrackingStatus.Idle)
    override val status: StateFlow<TrackingStatus> = _status.asStateFlow()

    /**
     * Updates the tracker status using a prioritized transition rule to prevent race conditions.
     * Priority: PERMISSION_MISSING > DATABASE_ERROR > UNKNOWN (Error) > Active = Idle.
     *
     * Transitions:
     * 1. Higher or equal priority always overrides.
     * 2. Healthy states (Active, Idle) can clear transient UNKNOWN errors.
     * 3. DATABASE_ERROR and PERMISSION_MISSING are sticky and require 'force' or explicit recovery to clear.
     */
    private fun transitionStatus(
        newStatus: TrackingStatus,
        force: Boolean = false,
    ) {
        _status.update { current ->
            val currentPrio = getStatusPriority(current)
            val newPrio = getStatusPriority(newStatus)

            if (force || newPrio >= currentPrio) newStatus else current
        }
    }

    private fun getStatusPriority(status: TrackingStatus): Int =
        when (status) {
            is TrackingStatus.Error ->
                when (status.type) {
                    TrackingStatus.ErrorType.PERMISSION_MISSING -> TrackingStatus.PRIORITY_PERMISSION_MISSING
                    TrackingStatus.ErrorType.DATABASE_ERROR -> TrackingStatus.PRIORITY_DATABASE_ERROR
                    else -> TrackingStatus.PRIORITY_TRANSIENT_ERROR
                }

            is TrackingStatus.Active, is TrackingStatus.Idle -> TrackingStatus.PRIORITY_HEALTHY
            else -> 0
        }

    private val trafficMutex = Mutex()
    private val initMutex = Mutex()
    private val updateMutex = Mutex()
    private val initializationFailed = AtomicBoolean(false)
    private val isInitializing = AtomicBoolean(false)
    private val initJob = AtomicReference<Job?>(null)
    private var permissionObserverJob: Job? = null
    private var simObserverJob: Job? = null

    init {
        isInitializing.set(true)
        scope.launch {
            try {
                startInitialization()
            } finally {
                initMutex.withLock {
                    isInitializing.set(false)
                }
            }
        }
        startAutomaticRecoveryObservers()
    }

    private suspend fun startInitialization() {
        val oldJob = initJob.getAndSet(currentCoroutineContext().job)
        oldJob?.cancel()
        try {
            oldJob?.join()
        } catch (_: Exception) {
            // job уже завершён или отменён
        }
        initializeWithRetry()
    }

    private fun startAutomaticRecoveryObservers() {
        startPermissionRecoveryObserver()
        startSimRecoveryObserver()
    }

    private fun startPermissionRecoveryObserver() {
        permissionObserverJob =
            scope.launch {
                try {
                    permissionStateNotifier.changes.collect {
                        if (permissionManager.hasPhoneStatePermission() &&
                            permissionManager.hasUsageStatsPermission()
                        ) {
                            checkAndScheduleAutoRecovery("permission change")
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("TrafficTracker: Permission observer failed", e)
                }
            }
    }

    private fun startSimRecoveryObserver() {
        simObserverJob =
            scope.launch {
                try {
                    simIdentityRepository.observeActiveSims().collect {
                        checkAndScheduleAutoRecovery("SIM change")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("TrafficTracker: SIM observer failed", e)
                }
            }
    }

    private suspend fun checkAndScheduleAutoRecovery(reason: String) {
        val shouldStart =
            initMutex.withLock {
                if (!isInitializing.get() && initializationFailed.compareAndSet(true, false)) {
                    isInitializing.set(true)
                    true
                } else {
                    false
                }
            }
        if (shouldStart) {
            AppLogger.d("TrafficTracker: attempting auto-recovery after $reason")
            scope.launch {
                try {
                    startInitialization()
                } finally {
                    initMutex.withLock {
                        isInitializing.set(false)
                    }
                }
            }
        }
    }

    private suspend fun initializeWithRetry() {
        initMutex.withLock {
            performInitializeWithRetry()
        }
    }

    private suspend fun performInitializeWithRetry() {
        for (attempt in 1..MAX_INIT_ATTEMPTS) {
            try {
                updateTrafficUsageLogic()
                initializationFailed.set(false)
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
        val shouldStart =
            initMutex.withLock {
                if (!isInitializing.get() && initializationFailed.compareAndSet(true, false)) {
                    isInitializing.set(true)
                    true
                } else {
                    false
                }
            }
        if (shouldStart) {
            AppLogger.d("TrafficTracker: attempting manual recovery")
            try {
                startInitialization()
            } finally {
                initMutex.withLock {
                    isInitializing.set(false)
                }
            }
        } else {
            updateTrafficUsage()
        }
    }

    fun stop() {
        permissionObserverJob?.cancel()
        permissionObserverJob = null
        simObserverJob?.cancel()
        simObserverJob = null
        initJob.getAndSet(null)?.cancel()
        isInitializing.set(false)
        initializationFailed.set(false)
    }

    suspend fun resetBaseline(identity: String) {
        if (identity.startsWith(Constants.SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
            AppLogger.w("TrafficTracker: resetBaseline called with invalid identity: $identity")
            return
        }
        simChangeHandler.withSimChangeLock {
            trafficMutex.withLock {
                if (!permissionChecker.checkPermissions(_status)) return@withLock
                baselineManager.resetBaseline(
                    identity = identity,
                    updateStatus = { transitionStatus(it) },
                    incrementRetryOnFailure = false, // Manual reset
                ) {
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

    /**
     * Updates mobile traffic usage for the current default data SIM.
     *
     * @throws TrafficUpdateTimeoutException if the operation times out.
     *
     * **Thread Safety:** Safe to call from any coroutine context. Holds internal mutex
     * during the entire update process to ensure data consistency.
     */
    suspend fun updateTrafficUsage() {
        try {
            updateTrafficUsageLogic()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            updateStatusOnError(e.message ?: "Permission missing", TrackingStatus.ErrorType.PERMISSION_MISSING)
        } catch (_: TrafficUpdateTimeoutException) {
            updateStatusOnError(TrafficUpdateTimeoutException.MESSAGE)
        } catch (e: Exception) {
            updateStatusOnError(e.message ?: "Unknown error")
        }
    }

    private fun updateStatusOnError(
        message: String?,
        type: TrackingStatus.ErrorType = TrackingStatus.ErrorType.UNKNOWN,
    ) {
        _status.update { current ->
            // Race protection: if a successful update already set status to Active,
            // do not overwrite it with a stale timeout error.
            if (message == TrafficUpdateTimeoutException.MESSAGE && current is TrackingStatus.Active) {
                current
            } else {
                val error = TrackingStatus.Error(type, message)
                val currentPrio = getStatusPriority(current)
                val newPrio = getStatusPriority(error)
                // Recovery: Success clearing UNKNOWN is handled in transitionStatus,
                // but here we are setting Error, so we follow priority.
                if (newPrio >= currentPrio) error else current
            }
        }
    }

    @Suppress("ThrowsCount")
    private suspend fun updateTrafficUsageLogic() {
        val result =
            withTimeoutOrNull(Constants.Flow.TRAFFIC_UPDATE_TOTAL_TIMEOUT_MS.milliseconds) {
                updateMutex.withLock {
                    try {
                        val activeSims = fetchActiveSims()
                        if (activeSims == null) {
                            updateStatusOnError("Timeout fetching active sims")
                            return@withLock
                        }

                        clearPreviousTimeoutError()

                        if (!permissionChecker.checkPermissions(_status)) return@withLock

                        handleActiveSims(activeSims)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: SecurityException) {
                        AppLogger.e("TrafficTracker: SecurityException during update", e)
                        throw e
                    } catch (e: Exception) {
                        AppLogger.e("TrafficTracker: Unexpected error during update", e)
                        throw e
                    }
                }
            }
        if (result == null) {
            AppLogger.e("TrafficTracker: Traffic update timed out")
            throw TrafficUpdateTimeoutException()
        }
    }

    private fun clearPreviousTimeoutError() {
        _status.update { current ->
            val isTimeoutError =
                (current is TrackingStatus.Error) &&
                    (current.type == TrackingStatus.ErrorType.UNKNOWN) &&
                    (
                        current.message == TrafficUpdateTimeoutException.MESSAGE ||
                            current.message == "Timeout fetching active sims"
                        )
            // We can use direct return here since it's an explicit clearing logic
            if (isTimeoutError) TrackingStatus.Idle else current
        }
    }

    private suspend fun fetchActiveSims(): List<ActiveSimInfo>? =
        withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
            simIdentityRepository.observeActiveSims().first()
        } ?: run {
            AppLogger.e("TrafficTracker: timeout fetching active sims")
            null
        }

    private suspend fun handleActiveSims(activeSims: List<ActiveSimInfo>) {
        if (activeSims.isEmpty()) {
            trafficMutex.withLock {
                baselineManager.cleanupRetries(emptySet())
                transitionStatus(TrackingStatus.Idle)
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
            errorHandler.handleInconsistentFirmware { transitionStatus(it) }
            return
        }

        if (result !is TrafficTargetResolver.TargetResult.Success) {
            AppLogger.w("TrafficTracker: resolution failed or SIMs inactive: $result")
            return
        }

        val target = result.target

        try {
            simChangeHandler.withSimChangeLock {
                val targetIdentity = simChangeHandler.handleSimChangeIfNeeded(target) { transitionStatus(it) }
                targetIdentity?.let { performTrafficUpdate(target, it) }
            }
        } catch (e: InvalidSlotException) {
            AppLogger.w("TrafficTracker: InvalidSlotException for slot ${target.slot}", e)
            transitionStatus(TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message))
        } catch (e: SimNotFoundException) {
            AppLogger.w("TrafficTracker: SimNotFoundException for slot ${target.slot}", e)
            transitionStatus(TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message))
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
                transitionStatus(TrackingStatus.Idle)
                return@withLock
            }

            // Ensure tracking window is initialized before fetching usage.
            // If it was 0, it becomes 'now' and baseline 0.
            simUsageResetRepository.ensureTrackingWindowInitialized(targetIdentity)

            val currentUsage =
                simUsageRepository
                    .getSimUsage(targetIdentity)
                    .firstWithTimeout(Constants.Flow.LONG_TIMEOUT_MS.milliseconds)
                    ?: run {
                        errorHandler.handleUsageReadTimeout(targetIdentity) { transitionStatus(it) }
                        return@withLock
                    }

            val currentRetryCount = baselineManager.getRetryCount(targetIdentity)
            val statsResult = baselineManager.getCurrentMobileBytes(currentUsage.lastResetDate)

            if (currentRetryCount != null) {
                if (currentRetryCount >= TrafficBaselineManager.MAX_BASELINE_RETRIES) {
                    baselineManager.removeRetry(targetIdentity)
                    errorHandler.handleBaselineRetryFailure(targetIdentity) { transitionStatus(it) }
                } else {
                    handleRetryAction(target, targetIdentity, currentRetryCount, statsResult)
                }
            } else {
                executeUpdateAndBaseline(target, targetIdentity, statsResult)
            }
        }
    }

    private suspend fun executeUpdateAndBaseline(
        target: TargetInfo,
        targetIdentity: String,
        statsResult: StatsResult,
    ) {
        if (statsResult !is StatsResult.Success) {
            errorHandler.handleStatsFailure(statsResult) { transitionStatus(it) }
        } else {
            val lastTotalBytes = fetchLastTotalBytes(targetIdentity, statsResult)
            if (lastTotalBytes == null) {
                errorHandler.handleLastTotalBytesTimeout(targetIdentity) { transitionStatus(it) }
            } else {
                performUpdateWrite(target, targetIdentity, statsResult.bytes, lastTotalBytes)
            }
        }
    }

    private suspend fun performUpdateWrite(
        target: TargetInfo,
        targetIdentity: String,
        currentBytes: Long,
        lastTotalBytes: Long,
    ) {
        val writeSuccess =
            withTimeoutOrNull(Constants.Flow.LONG_TIMEOUT_MS.milliseconds) {
                deltaCalculator.updateTrafficBaselineAndUsage(
                    targetIdentity,
                    currentBytes,
                    lastTotalBytes,
                    target,
                )
                simChangeHandler.updateLastDataSubId(target.dataSubId)
                dataChangeNotifier.notifyDataChanged(updateWidget = true, force = false)
                true
            } ?: false

        if (!writeSuccess) {
            AppLogger.e("TrafficTracker: Traffic update write timed out for $targetIdentity")
            transitionStatus(
                TrackingStatus.Error(
                    TrackingStatus.ErrorType.UNKNOWN,
                    "Traffic update write timeout",
                ),
                )
        } else {
            transitionStatus(TrackingStatus.Active, force = true)
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
                dataChangeNotifier.notifyDataChanged(updateWidget = true, force = false)
            }
            baselineManager.removeRetry(targetIdentity)
            transitionStatus(TrackingStatus.Active, force = true)
            AppLogger.d("Traffic baseline reset on retry for $targetIdentity to ${statsResult.bytes}")
        } else {
            val nextRetry = currentPending + 1
            if (nextRetry >= TrafficBaselineManager.MAX_BASELINE_RETRIES) {
                baselineManager.removeRetry(targetIdentity)
                errorHandler.handleBaselineRetryFailure(targetIdentity) { transitionStatus(it) }
            } else {
                baselineManager.setRetryCount(targetIdentity, nextRetry)
                val reason =
                    if (statsResult is StatsResult.Error) {
                        statsResult.exception.message
                    } else {
                        "Stats unavailable"
                    }
                transitionStatus(
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.UNKNOWN,
                        reason ?: "Stats unavailable, retry pending",
                    ),
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
