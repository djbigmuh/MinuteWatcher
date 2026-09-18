package com.example.minutewatcher.core.data

import androidx.annotation.VisibleForTesting
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.TimeProvider
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
import com.example.minutewatcher.core.model.TrackingStatusMachine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

internal class TrafficUpdateTimeoutException(
    val startElapsed: Long = 0L,
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
 * This class acts as an orchestrator, delegating specific responsibilities to specialized components.
 * Single-flight initialization is enforced via an atomic flag, ensuring cleanup upon cancellation
 * is completely non-blocking.
 *
 * **Lifecycle:** Initialization starts immediately upon construction in the `init` block.
 * Eager injection in the Application class ensures tracking is active early in the process lifecycle.
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
        private val permissionChecker: TrafficPermissionChecker,
        private val permissionStateNotifier: PermissionStateNotifier,
        private val dataChangeNotifier: DataChangeNotifier,
        private val timeProvider: TimeProvider,
        @ApplicationScope private val scope: CoroutineScope,
    ) : TrackingStatusProvider {
        override val key: TrackingKey = TrackingKey.TRAFFIC

        @VisibleForTesting
        internal val statusMachine = TrackingStatusMachine()
        override val status: StateFlow<TrackingStatus> = statusMachine.status

        /**
         * Updates the tracker status using prioritized canonical rules.
         */
        private fun transitionStatus(
            newStatus: TrackingStatus,
            force: Boolean = false,
        ) {
            AppLogger.d("TrafficTracker: transitioning to $newStatus (force=$force)")
            statusMachine.transition(newStatus, force)
        }

        private val trafficMutex = Mutex()
        private val updateMutex = Mutex()
        private val initializationFailed = AtomicBoolean(false)
        private val isInitializing = AtomicBoolean(false)

        @VisibleForTesting
        internal val lastSuccessElapsed = AtomicLong(0L)

        @VisibleForTesting
        internal val initJob = AtomicReference<Job?>(null)
        private var lastObservedDataSubId: Int? = null

        private fun tryClaimInitialization(): Boolean = isInitializing.compareAndSet(false, true)

        private fun releaseInitialization() {
            isInitializing.set(false)
        }

        init {
            if (tryClaimInitialization()) {
                scope.launch {
                    try {
                        startInitialization()
                    } finally {
                        releaseInitialization()
                    }
                }
            }
            startAutomaticRecoveryObservers()
        }

        @VisibleForTesting
        internal suspend fun startInitialization() {
            val oldJob = initJob.getAndSet(currentCoroutineContext().job)
            oldJob?.cancel()
            try {
                oldJob?.join()
            } catch (_: Exception) {
                // job уже завершён или отменён
            }
            currentCoroutineContext().ensureActive()
            performInitializeWithRetry()
        }

        private fun startAutomaticRecoveryObservers() {
            startPermissionRecoveryObserver()
            startSimRecoveryObserver()
        }

        private fun startPermissionRecoveryObserver() {
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
            scope.launch {
                try {
                    simIdentityRepository.observeActiveSims().collect { sims ->
                        checkAndScheduleAutoRecovery("SIM change")
                        handleDefaultDataChange(sims)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("TrafficTracker: SIM observer failed", e)
                }
            }
        }

        @Suppress("ReturnCount")
        private suspend fun handleDefaultDataChange(sims: List<ActiveSimInfo>) {
            val currentDataSubId = sims.firstOrNull { it.isDefaultData }?.subscriptionId ?: -1
            val previous = lastObservedDataSubId
            lastObservedDataSubId = currentDataSubId

            if (previous == null) return // First emission handled by initialization
            if (previous == currentDataSubId) return // No change in default data SIM

            if (currentDataSubId == -1) {
                AppLogger.d("TrafficTracker: default data SIM disappeared, skipping update")
                return
            }

            AppLogger.d("TrafficTracker: default data SIM changed ($previous -> $currentDataSubId), updating traffic")
            try {
                updateTrafficUsage()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("TrafficTracker: SIM-change triggered update failed", e)
            }
        }

        private fun checkAndScheduleAutoRecovery(reason: String) {
            val shouldStart =
                if (initializationFailed.get() && tryClaimInitialization()) {
                    true
                } else {
                    false
                }
            if (shouldStart) {
                AppLogger.d("TrafficTracker: attempting auto-recovery after $reason")
                scope.launch {
                    try {
                        startInitialization()
                    } finally {
                        releaseInitialization()
                    }
                }
            }
        }

        private suspend fun performInitializeWithRetry() {
            val startElapsed = timeProvider.elapsedRealtime()
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
                        handleInitError(startElapsed, e)
                        return
                    }
                    if (attempt >= MAX_INIT_ATTEMPTS) break

                    val delayMs = INIT_RETRY_BASE_MS * (1 shl (attempt - 1))
                    AppLogger.w("TrafficTracker: init attempt $attempt failed, retry in ${delayMs}ms", e)
                    delay(delayMs.milliseconds)
                }
            }
            initializationFailed.set(true)
            handleInitError(
                startElapsed,
                IllegalStateException("Initialization failed after $MAX_INIT_ATTEMPTS attempts"),
            )
        }

        private fun handleInitError(
            startElapsed: Long,
            e: Exception,
        ) {
            val type =
                if (e is SecurityException) {
                    TrackingStatus.ErrorType.PERMISSION_MISSING
                } else {
                    TrackingStatus.ErrorType.UNKNOWN
                }
            val code =
                if (e is TrafficUpdateTimeoutException) {
                    TrackingStatus.ErrorCode.TIMEOUT
                } else {
                    null
                }
            updateStatusOnError(
                startElapsed,
                e.message ?: "Initialization failed",
                type = type,
                code = code,
            )
        }

        private fun isTerminalInitError(e: Exception): Boolean =
            (e is SecurityException) || (e is TrafficUpdateTimeoutException)

        override suspend fun recover() {
            val shouldStart =
                if (initializationFailed.get() && tryClaimInitialization()) {
                    true
                } else {
                    false
                }
            if (shouldStart) {
                AppLogger.d("TrafficTracker: attempting manual recovery")
                try {
                    performInitializeWithRetry()
                } finally {
                    releaseInitialization()
                }
            } else {
                updateTrafficUsage()
            }
        }

        suspend fun resetBaseline(identity: String) {
            val startElapsed = timeProvider.elapsedRealtime()
            if (identity.startsWith(Constants.SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("TrafficTracker: resetBaseline called with invalid identity: $identity")
                return
            }
            simChangeHandler.withSimChangeLock {
                trafficMutex.withLock {
                    if (!permissionChecker.checkPermissions { transitionStatus(it) }) return@withLock
                    baselineManager.resetBaseline(
                        identity = identity,
                        updateStatus = {
                            if (it is TrackingStatus.Active) {
                                lastSuccessElapsed.set(timeProvider.elapsedRealtime())
                                transitionStatus(it, force = true)
                            } else if (it is TrackingStatus.Error) {
                                updateStatusOnError(startElapsed, it.message, it.type, it.code)
                            } else {
                                transitionStatus(it)
                            }
                        },
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
            val startElapsed = timeProvider.elapsedRealtime()
            try {
                updateTrafficUsageLogic()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                updateStatusOnError(
                    startElapsed,
                    e.message ?: "Permission missing",
                    TrackingStatus.ErrorType.PERMISSION_MISSING,
                )
            } catch (e: TrafficUpdateTimeoutException) {
                updateStatusOnError(
                    if (e.startElapsed != 0L) e.startElapsed else startElapsed,
                    TrafficUpdateTimeoutException.MESSAGE,
                    code = TrackingStatus.ErrorCode.TIMEOUT,
                )
            } catch (e: Exception) {
                updateStatusOnError(startElapsed, e.message ?: "Unknown error")
            }
        }

        private fun updateStatusOnError(
            startElapsed: Long,
            message: String?,
            type: TrackingStatus.ErrorType = TrackingStatus.ErrorType.UNKNOWN,
            code: TrackingStatus.ErrorCode? = null,
        ) {
            val lastSuccess = lastSuccessElapsed.get()
            if (startElapsed != 0L && lastSuccess > startElapsed) {
                AppLogger.d(
                    "TrafficTracker: suppressing stale error " +
                        "(success at $lastSuccess > start at $startElapsed)",
                )
                return
            }

            transitionStatus(TrackingStatus.Error(type, message, code = code))
        }

        @Suppress("ThrowsCount")
        private suspend fun updateTrafficUsageLogic() {
            val startElapsed = timeProvider.elapsedRealtime()
            val result =
                withTimeoutOrNull(Constants.Flow.TRAFFIC_UPDATE_TOTAL_TIMEOUT_MS.milliseconds) {
                    updateMutex.withLock {
                        try {
                            val activeSims = fetchActiveSims()
                            if (activeSims == null) {
                                updateStatusOnError(
                                    startElapsed,
                                    "Timeout fetching active sims",
                                    code = TrackingStatus.ErrorCode.TIMEOUT,
                                )
                                return@withLock
                            }

                            clearPreviousTimeoutError()

                            if (!permissionChecker.checkPermissions { transitionStatus(it) }) return@withLock

                            handleActiveSims(startElapsed, activeSims)
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
                throw TrafficUpdateTimeoutException(startElapsed)
            }
        }

        private fun clearPreviousTimeoutError() {
            val current = statusMachine.current
            val isTimeoutError = (current is TrackingStatus.Error) && current.code == TrackingStatus.ErrorCode.TIMEOUT

            if (isTimeoutError) {
                transitionStatus(TrackingStatus.Idle, force = true)
            }
        }

        private suspend fun fetchActiveSims(): List<ActiveSimInfo>? =
            withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                simIdentityRepository.observeActiveSims().first()
            } ?: run {
                AppLogger.e("TrafficTracker: timeout fetching active sims")
                null
            }

        private suspend fun handleActiveSims(
            startElapsed: Long,
            activeSims: List<ActiveSimInfo>,
        ) {
            if (activeSims.isEmpty()) {
                trafficMutex.withLock {
                    baselineManager.cleanupRetries(emptySet())
                    lastSuccessElapsed.set(timeProvider.elapsedRealtime())
                    transitionStatus(TrackingStatus.Idle)
                }
                return
            }

            val activeIdentities = activeSims.asSequence().map { it.identity.stableId }.toSet()
            trafficMutex.withLock {
                baselineManager.cleanupRetries(activeIdentities)
            }

            processActiveSimsTraffic(startElapsed, activeSims)
        }

        @Suppress("ReturnCount")
        private suspend fun processActiveSimsTraffic(
            startElapsed: Long,
            activeSims: List<ActiveSimInfo>,
        ) {
            val result = targetResolver.resolve(activeSims)

            if (result is TrafficTargetResolver.TargetResult.InconsistentFirmware) {
                baselineManager.cleanupRetries(emptySet())
                updateStatusOnError(
                    startElapsed,
                    "Inconsistent firmware state",
                    type = TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION,
                )
                return
            }

            if (result is TrafficTargetResolver.TargetResult.NoDefaultData) {
                AppLogger.d("TrafficTracker: no default data SIM, transitioning to Idle")
                transitionStatus(TrackingStatus.Idle)
                return
            }

            if (result !is TrafficTargetResolver.TargetResult.Success) {
                AppLogger.w("TrafficTracker: resolution failed or SIMs inactive: $result")
                return
            }

            val target = result.target
            val targetSimInfo = activeSims.find { it.slotIndex == target.slot }
            if (targetSimInfo?.identity?.isStable == false) {
                AppLogger.w("TrafficTracker: skipping traffic update for unstable identity on slot ${target.slot}")
                transitionStatus(TrackingStatus.Idle)
                return
            }

            try {
                simChangeHandler.withSimChangeLock {
                    val targetIdentity =
                        simChangeHandler.handleSimChangeIfNeeded(target) {
                            if (it is TrackingStatus.Active) {
                                lastSuccessElapsed.set(timeProvider.elapsedRealtime())
                                transitionStatus(it, force = true)
                            } else if (it is TrackingStatus.Error) {
                                updateStatusOnError(startElapsed, it.message, it.type, it.code)
                            } else {
                                transitionStatus(it)
                            }
                        }
                    targetIdentity?.let { performTrafficUpdate(startElapsed, target, it) }
                }
            } catch (e: InvalidSlotException) {
                AppLogger.w("TrafficTracker: InvalidSlotException for slot ${target.slot}", e)
                updateStatusOnError(startElapsed, e.message, type = TrackingStatus.ErrorType.UNKNOWN)
            } catch (e: SimNotFoundException) {
                AppLogger.w("TrafficTracker: SimNotFoundException for slot ${target.slot}", e)
                updateStatusOnError(startElapsed, e.message, type = TrackingStatus.ErrorType.UNKNOWN)
            }
        }

        private suspend fun performTrafficUpdate(
            startElapsed: Long,
            target: TargetInfo,
            targetIdentity: String,
        ) {
            trafficMutex.withLock {
                val currentActive =
                    simIdentityRepository
                        .getActiveIdentities()
                        .firstWithTimeout(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds)
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
                        .firstWithTimeout(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds)
                        ?: run {
                            updateStatusOnError(
                                startElapsed,
                                "Timeout reading simUsage",
                                code = TrackingStatus.ErrorCode.TIMEOUT,
                            )
                            return@withLock
                        }

                val currentRetryCount = baselineManager.getRetryCount(targetIdentity)
                val statsResult = baselineManager.getCurrentMobileBytes(currentUsage.lastResetDate)

                if (currentRetryCount != null) {
                    if (currentRetryCount >= TrafficBaselineManager.MAX_BASELINE_RETRIES) {
                        baselineManager.removeRetry(targetIdentity)
                        updateStatusOnError(
                            startElapsed,
                            "Baseline reset failed for $targetIdentity after all retries",
                            type = TrackingStatus.ErrorType.DATABASE_ERROR,
                        )
                    } else {
                        handleRetryAction(startElapsed, target, targetIdentity, currentRetryCount, statsResult)
                    }
                } else {
                    executeUpdateAndBaseline(startElapsed, target, targetIdentity, statsResult)
                }
            }
        }

        private suspend fun executeUpdateAndBaseline(
            startElapsed: Long,
            target: TargetInfo,
            targetIdentity: String,
            statsResult: StatsResult,
        ) {
            if (statsResult !is StatsResult.Success) {
                val message =
                    if (statsResult is StatsResult.Error) {
                        statsResult.exception.message
                    } else {
                        "Stats unavailable"
                    }
                updateStatusOnError(startElapsed, message)
            } else {
                val lastTotalBytes = fetchLastTotalBytes(targetIdentity, statsResult)
                if (lastTotalBytes == null) {
                    updateStatusOnError(
                        startElapsed,
                        "Timeout reading lastTotalBytes",
                        code = TrackingStatus.ErrorCode.TIMEOUT,
                    )
                } else {
                    performUpdateWrite(startElapsed, target, targetIdentity, statsResult.bytes, lastTotalBytes)
                }
            }
        }

        private suspend fun performUpdateWrite(
            startElapsed: Long,
            target: TargetInfo,
            targetIdentity: String,
            currentBytes: Long,
            lastTotalBytes: Long,
        ) {
            val writeSuccess =
                withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
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
                updateStatusOnError(
                    startElapsed,
                    "Traffic update write timeout",
                    code = TrackingStatus.ErrorCode.TIMEOUT,
                )
            } else {
                lastSuccessElapsed.set(timeProvider.elapsedRealtime())
                transitionStatus(TrackingStatus.Active, force = true)
            }
        }

        private suspend fun handleRetryAction(
            startElapsed: Long,
            target: TargetInfo,
            targetIdentity: String,
            currentPending: Int,
            statsResult: StatsResult,
        ) {
            if (statsResult is StatsResult.Success) {
                withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                    withContext(NonCancellable) {
                        simUsageResetRepository.setLastTotalBytes(targetIdentity, statsResult.bytes)
                        simChangeHandler.updateLastDataSubId(target.dataSubId)
                        dataChangeNotifier.notifyDataChanged(updateWidget = true, force = false)
                    }
                } ?: AppLogger.e("TrafficTracker: retry action baseline write timed out")
                baselineManager.removeRetry(targetIdentity)
                lastSuccessElapsed.set(timeProvider.elapsedRealtime())
                transitionStatus(TrackingStatus.Active, force = true)
                AppLogger.d("Traffic baseline reset on retry for $targetIdentity to ${statsResult.bytes}")
            } else {
                val nextRetry = currentPending + 1
                if (nextRetry >= TrafficBaselineManager.MAX_BASELINE_RETRIES) {
                    baselineManager.removeRetry(targetIdentity)
                    updateStatusOnError(
                        startElapsed,
                        "Baseline reset failed for $targetIdentity after all retries",
                        type = TrackingStatus.ErrorType.DATABASE_ERROR,
                    )
                } else {
                    baselineManager.setRetryCount(targetIdentity, nextRetry)
                    val reason =
                        if (statsResult is StatsResult.Error) {
                            statsResult.exception.message
                        } else {
                            "Stats unavailable"
                        }
                    updateStatusOnError(
                        startElapsed,
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
                .firstWithTimeout(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds)
        }

        private companion object {
            private const val MAX_INIT_ATTEMPTS = 3
            private const val INIT_RETRY_BASE_MS = 1000L
        }
    }
