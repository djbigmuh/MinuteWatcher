package com.example.minutewatcher.core.data

import androidx.annotation.VisibleForTesting
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.domain.InvalidSlotException
import com.example.minutewatcher.core.domain.SimNotFoundException
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.TrackingKey
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.core.model.TrackingStatusMachine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * Represents the outcome of a traffic update operation.
 *
 * Outcomes:
 * - [Completed]: Traffic update cycle finished without errors and applied a write
 *   (traffic delta charged or baseline set/reset), or completed normally without write
 *   because delta was below [Constants.Traffic.MIN_DELTA_MB].
 * - [Skipped]: Traffic update cycle intentionally skipped usage updating due to:
 *   a) Active SIMs list is empty.
 *   b) Stale SIM input during target resolution.
 *   c) No default data SIM.
 *   d) No active SIMs during target resolution.
 *   e) Target SIM identity no longer active.
 *   f) Unstable target SIM identity.
 *   g) Default data SIM change handling returned null identity.
 *   h) Update write rejected due to accounting window mismatch.
 * - [TimedOut]: Traffic update cycle or underlying reads/writes timed out.
 * - [Failed]: Traffic update cycle failed with an error.
 */
sealed interface TrafficUpdateOutcome {
    /**
     * Traffic update completed successfully.
     */
    data object Completed : TrafficUpdateOutcome

    /**
     * Traffic update was skipped.
     */
    data object Skipped : TrafficUpdateOutcome

    /**
     * Traffic update timed out.
     */
    data class TimedOut(
        val startElapsed: Long,
    ) : TrafficUpdateOutcome

    /**
     * Traffic update failed with an error.
     */
    data class Failed(
        val type: TrackingStatus.ErrorType,
        val code: TrackingStatus.ErrorCode?,
    ) : TrafficUpdateOutcome
}

/**
 * Tracks mobile traffic usage for the device.
 * Attributes all mobile traffic to the CURRENT DEFAULT DATA SIM.
 *
 * This class acts as an orchestrator, delegating specific responsibilities to specialized components.
 * Single-flight initialization is enforced via an atomic flag, ensuring cleanup upon cancellation
 * is completely non-blocking.
 *
 * **Lifecycle:** Construction of [TrafficTracker] has no side effects. Explicit initialization and recovery
 * observers are started via [ensureStarted], which should be called from [android.app.Application.onCreate].
 *
 * NOTE: Suppressing TooManyFunctions as this class is the central orchestrator for traffic tracking,
 * delegating to specialized components while maintaining the primary tracking lifecycle.
 */
@Singleton
@Suppress("TooManyFunctions", "LargeClass")
class TrafficTracker
    @Inject
    constructor(
        private val deps: TrafficTrackerDependencies,
        @ApplicationScope private val scope: CoroutineScope,
    ) : TrackingStatusProvider {
        private val simIdentityRepository get() = deps.simIdentityRepository
        private val simUsageRepository get() = deps.simUsageRepository
        private val simUsageResetRepository get() = deps.simUsageResetRepository
        private val permissionManager get() = deps.permissionManager
        private val baselineManager get() = deps.baselineManager
        private val deltaCalculator get() = deps.deltaCalculator
        private val simChangeHandler get() = deps.simChangeHandler
        private val targetResolver get() = deps.targetResolver
        private val permissionChecker get() = deps.permissionChecker
        private val permissionStateNotifier get() = deps.permissionStateNotifier
        private val dataChangeNotifier get() = deps.dataChangeNotifier
        private val timeProvider get() = deps.timeProvider
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
        private val isInitStarted = AtomicBoolean(false)
        private val bothPermissionsGranted = AtomicBoolean(false)

        private class InitToken {
            var started: Boolean = false
            var superseded: Boolean = false
        }

        private val currentInitToken = AtomicReference<InitToken?>(null)

        private fun releaseInitialization(token: InitToken) {
            currentInitToken.compareAndSet(token, null)
        }

        private suspend fun runInitializationWithToken(token: InitToken) {
            val shouldRun =
                synchronized(token) {
                    if (token.superseded) {
                        false
                    } else {
                        token.started = true
                        true
                    }
                }
            if (shouldRun) {
                startInitialization()
            }
        }

        @VisibleForTesting
        internal val lastSuccessElapsed = AtomicLong(0L)

        @VisibleForTesting
        internal val initJob = AtomicReference<Job?>(null)

        @VisibleForTesting
        internal val permissionObserverJob = AtomicReference<Job?>(null)

        @VisibleForTesting
        internal val simObserverJob = AtomicReference<Job?>(null)
        private var lastObservedDataSubId: Int? = null

        /**
         * Explicitly starts the traffic tracking initialization and automatic recovery observers.
         *
         * This method is idempotent and thread-safe. Subsequent calls are no-ops.
         * Should be called from Application.onCreate after dependencies are ready.
         *
         * If initialization is claimed but not yet started when [updateTrafficUsage] is called,
         * [updateTrafficUsage] atomically supersedes the initialization, executing it instead.
         */
        fun ensureStarted() {
            if (isInitStarted.compareAndSet(false, true)) {
                val token = InitToken()
                if (currentInitToken.compareAndSet(null, token)) {
                    scope.launch {
                        try {
                            runInitializationWithToken(token)
                        } finally {
                            releaseInitialization(token)
                        }
                    }
                }
            }
            startAutomaticRecoveryObservers()
        }

        /**
         * Starts initialization, waiting for the previous initialization job up to
         * [Constants.Flow.SHORT_TIMEOUT_MS]. If the timeout expires, initialization proceeds
         * without waiting further for the previous job.
         */
        @VisibleForTesting
        internal suspend fun startInitialization() {
            val newJob =
                scope.launch(start = CoroutineStart.LAZY) {
                    performInitializeWithRetry()
                }
            val oldJob = initJob.getAndSet(newJob)
            if (oldJob != null) {
                oldJob.cancel()
                val completed =
                    withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                        oldJob.join()
                        true
                    } ?: false
                if (!completed) {
                    AppLogger.w(
                        "TrafficTracker: previous initialization job did not complete within timeout, proceeding",
                    )
                }
            }
            currentCoroutineContext().ensureActive()
            newJob.start()
            newJob.join()
        }

        private fun startAutomaticRecoveryObservers() {
            startPermissionRecoveryObserver()
            startSimRecoveryObserver()
        }

        private val observerLock = Any()

        private fun startPermissionRecoveryObserver() {
            synchronized(observerLock) {
                val existing = permissionObserverJob.get()
                if (existing == null || !existing.isActive) {
                    val previous = permissionObserverJob.getAndSet(null)
                    previous?.cancel()
                    val newJob = scope.launch { runPermissionObserver() }
                    if (!permissionObserverJob.compareAndSet(null, newJob)) {
                        newJob.cancel()
                    }
                }
            }
        }

        private suspend fun runPermissionObserver() {
            var attempt = 0
            try {
                bothPermissionsGranted.set(
                    permissionManager.hasPhoneStatePermission() &&
                        permissionManager.hasUsageStatsPermission(),
                )
                permissionStateNotifier.changes
                    .retryWhen { cause, _ -> handleObserverError(cause, "Permission", ++attempt) }
                    .collect {
                        attempt = 0
                        handlePermissionChangeSafe()
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("TrafficTracker: Permission observer terminated", e)
            }
        }

        private suspend fun handleObserverError(
            cause: Throwable,
            observerName: String,
            attempt: Int,
        ): Boolean {
            if (cause is CancellationException) throw cause
            if (attempt > MAX_OBSERVER_RETRIES) {
                AppLogger.e(
                    "TrafficTracker: $observerName observer max retries ($MAX_OBSERVER_RETRIES) exhausted",
                    cause,
                )
                transitionStatus(
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.UNKNOWN,
                        "$observerName observer failed: ${cause.message}",
                    ),
                    force = true,
                )
                return false
            }
            val delayMs =
                minOf(
                    OBSERVER_RETRY_BASE_MS * (1L shl ((attempt - 1).coerceAtMost(30))),
                    OBSERVER_RETRY_MAX_MS,
                )
            AppLogger.w(
                "TrafficTracker: $observerName observer error (attempt $attempt), retrying in ${delayMs}ms",
                cause,
            )
            delay(delayMs.milliseconds)
            return true
        }

        private suspend fun handlePermissionChangeSafe() {
            try {
                handlePermissionChange()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("TrafficTracker: Error handling permission change", e)
                transitionStatus(
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.UNKNOWN,
                        "Error handling permission change: ${e.message}",
                    ),
                    force = true,
                )
            }
        }

        private fun handlePermissionChange() {
            val bothGranted =
                permissionManager.hasPhoneStatePermission() &&
                    permissionManager.hasUsageStatsPermission()
            val previousGranted = bothPermissionsGranted.getAndSet(bothGranted)
            if (bothGranted) {
                if (!previousGranted) {
                    AppLogger.d("TrafficTracker: permissions granted transition detected, updating traffic")
                    launchTrafficUpdate("Traffic update after permissions granted transition failed")
                }
                checkAndScheduleAutoRecovery("permission change")
            } else if (previousGranted) {
                AppLogger.d("TrafficTracker: permissions revoked transition detected, updating traffic")
                launchTrafficUpdate("Traffic update after permissions revoked transition failed")
            }
        }

        private fun launchTrafficUpdate(errorMessage: String) {
            scope.launch {
                try {
                    updateTrafficUsage()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("TrafficTracker: $errorMessage", e)
                }
            }
        }

        private fun startSimRecoveryObserver() {
            synchronized(observerLock) {
                val existing = simObserverJob.get()
                if (existing == null || !existing.isActive) {
                    val previous = simObserverJob.getAndSet(null)
                    previous?.cancel()
                    val newJob = scope.launch { runSimObserver() }
                    if (!simObserverJob.compareAndSet(null, newJob)) {
                        newJob.cancel()
                    }
                }
            }
        }

        private suspend fun runSimObserver() {
            var attempt = 0
            try {
                lastObservedDataSubId = null
                simIdentityRepository
                    .observeActiveSims()
                    .retryWhen { cause, _ -> handleObserverError(cause, "SIM", ++attempt) }
                    .collect { sims ->
                        attempt = 0
                        handleSimChangeSafe(sims)
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("TrafficTracker: SIM observer terminated", e)
            }
        }

        private suspend fun handleSimChangeSafe(sims: List<ActiveSimInfo>) {
            try {
                checkAndScheduleAutoRecovery("SIM change")
                handleDefaultDataChange(sims)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("TrafficTracker: Error handling SIM change event", e)
                transitionStatus(
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.UNKNOWN,
                        "Error handling SIM change event: ${e.message}",
                    ),
                    force = true,
                )
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
            if (initializationFailed.get()) {
                val token = InitToken()
                if (currentInitToken.compareAndSet(null, token)) {
                    AppLogger.d("TrafficTracker: attempting auto-recovery after $reason")
                    scope.launch {
                        try {
                            runInitializationWithToken(token)
                        } finally {
                            releaseInitialization(token)
                        }
                    }
                }
            }
        }

    private suspend fun performInitializeWithRetry(): TrafficUpdateOutcome {
            val startElapsed = timeProvider.elapsedRealtime()
            var lastException: Exception? = null
        var finalOutcome: TrafficUpdateOutcome? = null
            for (attempt in 1..MAX_INIT_ATTEMPTS) {
                when (val result = executeInitAttempt(startElapsed)) {
                    is InitAttemptResult.Success -> {
                        initializationFailed.set(false)
                        finalOutcome = result.outcome
                    }

                    is InitAttemptResult.TerminalError -> {
                        initializationFailed.set(true)
                        finalOutcome = result.outcome
                    }

                    is InitAttemptResult.RetryableError -> {
                        lastException = result.exception
                        if (attempt < MAX_INIT_ATTEMPTS) {
                            val delayMs = INIT_RETRY_BASE_MS * (1 shl (attempt - 1))
                            AppLogger.w(
                                "TrafficTracker: init attempt $attempt failed, retry in ${delayMs}ms",
                                result.exception,
                            )
                            delay(delayMs.milliseconds)
                        }
                    }
                }
                if (finalOutcome != null) {
                    return finalOutcome
                }
            }
        initializationFailed.set(true)
        AppLogger.e("TrafficTracker: initialization failed after $MAX_INIT_ATTEMPTS attempts", lastException)
        val ex = IllegalStateException("Initialization failed after $MAX_INIT_ATTEMPTS attempts", lastException)
        handleInitError(startElapsed, ex)
        return TrafficUpdateOutcome.Failed(TrackingStatus.ErrorType.UNKNOWN, null)
    }

    private sealed interface InitAttemptResult {
        data class Success(
            val outcome: TrafficUpdateOutcome,
        ) : InitAttemptResult

        data class TerminalError(
            val outcome: TrafficUpdateOutcome,
        ) : InitAttemptResult

        data class RetryableError(
            val exception: Exception,
        ) : InitAttemptResult
    }

    private suspend fun executeInitAttempt(startElapsed: Long): InitAttemptResult =
        try {
            val outcome = updateTrafficUsageLogic()
            InitAttemptResult.Success(outcome)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isTerminalInitError(e)) {
                AppLogger.e("TrafficTracker: terminal init error", e)
                handleInitError(startElapsed, e)
                InitAttemptResult.TerminalError(mapTerminalInitOutcome(startElapsed, e))
            } else {
                InitAttemptResult.RetryableError(e)
            }
        }

    private fun mapTerminalInitOutcome(
        startElapsed: Long,
        e: Exception,
    ): TrafficUpdateOutcome =
        if (e is SecurityException) {
            TrafficUpdateOutcome.Failed(TrackingStatus.ErrorType.PERMISSION_MISSING, null)
        } else if (e is TrafficUpdateTimeoutException) {
            TrafficUpdateOutcome.TimedOut(startElapsed)
        } else {
            TrafficUpdateOutcome.Failed(TrackingStatus.ErrorType.UNKNOWN, null)
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
            startAutomaticRecoveryObservers()

            if (initializationFailed.get()) {
                val token = InitToken().apply { started = true }
                if (currentInitToken.compareAndSet(null, token)) {
                    AppLogger.d("TrafficTracker: attempting manual recovery")
                    try {
                        startInitialization()
                    } finally {
                        releaseInitialization(token)
                    }
                    return
                }
            }
            updateTrafficUsage()
        }

        suspend fun resetBaseline(identity: String) {
            val startElapsed = timeProvider.elapsedRealtime()
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
         * Returns [TrafficUpdateOutcome] representing the result of this update cycle:
         * - [TrafficUpdateOutcome.Completed] when traffic usage or baseline is updated, or delta is below threshold.
         * - [TrafficUpdateOutcome.Skipped] when the cycle is intentionally skipped (e.g. no active SIMs,
         *   stale input, unstable identity, or accounting window mismatch).
         * - [TrafficUpdateOutcome.TimedOut] when the operation or underlying queries time out.
         * - [TrafficUpdateOutcome.Failed] when an error occurs during processing.
         *
         * If initialization is currently claimed but has not yet started executing,
         * this call atomically intercepts (supersedes) the initialization, executing
         * the initialization contract instead of a normal update cycle.
         *
         * **Thread Safety:** Safe to call from any coroutine context. Holds internal mutex
         * during the entire update process to ensure data consistency.
         */
        suspend fun updateTrafficUsage(): TrafficUpdateOutcome {
            val startElapsed = timeProvider.elapsedRealtime()
            val token = currentInitToken.get()
            if (token != null) {
                val intercepted =
                    synchronized(token) {
                        if (!token.started && !token.superseded) {
                            token.superseded = true
                            token.started = true
                            true
                        } else {
                            false
                        }
                    }
                if (intercepted) {
                    currentInitToken.compareAndSet(token, null)
                    return runCatchingTrafficUpdate(startElapsed) {
                        performInitializeWithRetry()
                    }
                }
            }

            return runCatchingTrafficUpdate(startElapsed) {
                updateTrafficUsageLogic()
            }
        }

        private suspend fun runCatchingTrafficUpdate(
            startElapsed: Long,
            block: suspend () -> TrafficUpdateOutcome,
        ): TrafficUpdateOutcome =
            try {
                val outcome = block()
                val currentStatus = statusMachine.current
                if (currentStatus is TrackingStatus.Error) {
                    if (currentStatus.code == TrackingStatus.ErrorCode.TIMEOUT) {
                        TrafficUpdateOutcome.TimedOut(startElapsed)
                    } else {
                        TrafficUpdateOutcome.Failed(currentStatus.type, currentStatus.code)
                    }
                } else {
                    outcome
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                updateStatusOnError(
                    startElapsed,
                    e.message ?: "Permission missing",
                    TrackingStatus.ErrorType.PERMISSION_MISSING,
                )
                TrafficUpdateOutcome.Failed(TrackingStatus.ErrorType.PERMISSION_MISSING, null)
            } catch (e: TrafficUpdateTimeoutException) {
                val elapsed = if (e.startElapsed != 0L) e.startElapsed else startElapsed
                updateStatusOnError(
                    elapsed,
                    TrafficUpdateTimeoutException.MESSAGE,
                    code = TrackingStatus.ErrorCode.TIMEOUT,
                )
                TrafficUpdateOutcome.TimedOut(elapsed)
            } catch (e: Exception) {
                updateStatusOnError(startElapsed, e.message ?: "Unknown error")
                TrafficUpdateOutcome.Failed(TrackingStatus.ErrorType.UNKNOWN, null)
            }

        @VisibleForTesting
        internal fun updateStatusOnError(
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
        private suspend fun updateTrafficUsageLogic(): TrafficUpdateOutcome {
            val startElapsed = timeProvider.elapsedRealtime()
            val outcome =
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
                                return@withLock TrafficUpdateOutcome.TimedOut(startElapsed)
                            }

                            clearPreviousTimeoutError()

                            if (!permissionChecker.checkPermissions { transitionStatus(it) }) {
                                return@withLock TrafficUpdateOutcome.Failed(
                                    TrackingStatus.ErrorType.PERMISSION_MISSING,
                                    null,
                                )
                            }

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
            if (outcome == null) {
                AppLogger.e("TrafficTracker: Traffic update timed out")
                throw TrafficUpdateTimeoutException(startElapsed)
            }
            return outcome
        }

        private fun clearPreviousTimeoutError() {
            val current = statusMachine.current
            val isTimeoutError = (current is TrackingStatus.Error) && current.code == TrackingStatus.ErrorCode.TIMEOUT

            if (isTimeoutError) {
                transitionStatus(TrackingStatus.Idle, force = true)
            }
        }

        private suspend fun fetchActiveSims(): List<ActiveSimInfo>? {
            val sims =
                simIdentityRepository
                    .observeActiveSims()
                    .firstWithTimeout(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds)
            if (sims == null) {
                AppLogger.e("TrafficTracker: timeout fetching active sims")
            }
            return sims
        }

        private suspend fun handleActiveSims(
            startElapsed: Long,
            activeSims: List<ActiveSimInfo>,
        ): TrafficUpdateOutcome {
            if (activeSims.isEmpty()) {
                trafficMutex.withLock {
                    baselineManager.cleanupRetries(emptySet())
                    lastSuccessElapsed.set(timeProvider.elapsedRealtime())
                    transitionStatus(TrackingStatus.Idle)
                }
                return TrafficUpdateOutcome.Skipped
            }

            val activeIdentities = activeSims.asSequence().map { it.identity.stableId }.toSet()
            trafficMutex.withLock {
                baselineManager.cleanupRetries(activeIdentities)
            }

            return processActiveSimsTraffic(startElapsed, activeSims)
        }

        @Suppress("ReturnCount")
        private suspend fun processActiveSimsTraffic(
            startElapsed: Long,
            activeSims: List<ActiveSimInfo>,
        ): TrafficUpdateOutcome {
            val target =
                when (val result = targetResolver.resolve(activeSims)) {
                    is TrafficTargetResolver.TargetResult.Success -> result.target
                    is TrafficTargetResolver.TargetResult.StaleInput -> {
                        AppLogger.w("TrafficTracker: stale SIM state during target resolution, skipping this cycle")
                        return TrafficUpdateOutcome.Skipped
                    }

                    is TrafficTargetResolver.TargetResult.NoDefaultData -> {
                        AppLogger.d("TrafficTracker: no default data SIM, transitioning to Idle")
                        transitionStatus(TrackingStatus.Idle)
                        return TrafficUpdateOutcome.Skipped
                    }

                    is TrafficTargetResolver.TargetResult.Failed -> {
                        updateStatusOnError(
                            startElapsed,
                            result.message ?: "Traffic target resolution failed",
                            type = TrackingStatus.ErrorType.UNKNOWN,
                        )
                        return TrafficUpdateOutcome.Failed(TrackingStatus.ErrorType.UNKNOWN, null)
                    }

                    is TrafficTargetResolver.TargetResult.NoActiveSims -> {
                        AppLogger.w("TrafficTracker: No active SIMs during target resolution")
                        return TrafficUpdateOutcome.Skipped
                    }
                }

            val targetSimInfo = activeSims.find { it.slotIndex == target.slot }
            if (targetSimInfo?.identity?.isStable == false) {
                AppLogger.w("TrafficTracker: skipping traffic update for unstable identity on slot ${target.slot}")
                transitionStatus(TrackingStatus.Idle)
                return TrafficUpdateOutcome.Skipped
            }

            return executeSimChangeAndTrafficUpdate(startElapsed, target)
        }

    private suspend fun executeSimChangeAndTrafficUpdate(
        startElapsed: Long,
        target: TargetInfo,
    ): TrafficUpdateOutcome =
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
                    if (targetIdentity != null) {
                        performTrafficUpdate(startElapsed, target, targetIdentity)
                    } else {
                        TrafficUpdateOutcome.Skipped
                    }
                }
            } catch (e: InvalidSlotException) {
                AppLogger.w("TrafficTracker: InvalidSlotException for slot ${target.slot}", e)
                updateStatusOnError(startElapsed, e.message, type = TrackingStatus.ErrorType.UNKNOWN)
                TrafficUpdateOutcome.Failed(TrackingStatus.ErrorType.UNKNOWN, null)
            } catch (e: SimNotFoundException) {
                AppLogger.w("TrafficTracker: SimNotFoundException for slot ${target.slot}", e)
                updateStatusOnError(startElapsed, e.message, type = TrackingStatus.ErrorType.UNKNOWN)
                TrafficUpdateOutcome.Failed(TrackingStatus.ErrorType.UNKNOWN, null)
            }

        private suspend fun performTrafficUpdate(
            startElapsed: Long,
            target: TargetInfo,
            targetIdentity: String,
        ): TrafficUpdateOutcome {
            return trafficMutex.withLock {
                if (!validateActiveIdentity(startElapsed, targetIdentity)) {
                    val currentStatus = statusMachine.current
                    return@withLock if (currentStatus is TrackingStatus.Error &&
                        currentStatus.code == TrackingStatus.ErrorCode.TIMEOUT
                    ) {
                        TrafficUpdateOutcome.TimedOut(startElapsed)
                    } else {
                        TrafficUpdateOutcome.Skipped
                    }
                }
                simUsageResetRepository.ensureTrackingWindowInitialized(targetIdentity)
                val expectedLastResetDate =
                    readResetDate(startElapsed, targetIdentity)
                        ?: return@withLock TrafficUpdateOutcome.TimedOut(startElapsed)
                val retryResult = readBaselineRetryCount(targetIdentity)
                if (retryResult.isFailure) {
                    updateStatusOnError(
                        startElapsed,
                        "Timeout reading baseline retry count for $targetIdentity",
                        code = TrackingStatus.ErrorCode.TIMEOUT,
                    )
                    return@withLock TrafficUpdateOutcome.TimedOut(startElapsed)
                }
                val currentRetryCount = retryResult.getOrNull()
                val statsResult = baselineManager.getCurrentMobileBytes(expectedLastResetDate)
                handleTrafficUpdateExecution(
                    startElapsed,
                    target,
                    targetIdentity,
                    currentRetryCount,
                    statsResult,
                    expectedLastResetDate,
                )
            }
        }

        private suspend fun validateActiveIdentity(
            startElapsed: Long,
            targetIdentity: String,
        ): Boolean {
            val currentActive =
                simIdentityRepository
                    .getActiveIdentities()
                    .firstWithTimeout(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds)

            val isValid = currentActive != null && targetIdentity in currentActive
            if (!isValid) {
                if (currentActive == null) {
                    AppLogger.w("TrafficTracker: Timeout reading activeIdentities")
                    updateStatusOnError(
                        startElapsed,
                        "Timeout reading activeIdentities",
                        code = TrackingStatus.ErrorCode.TIMEOUT,
                    )
                } else {
                    AppLogger.d("TrafficTracker: $targetIdentity no longer active, skipping")
                    transitionStatus(TrackingStatus.Idle)
                }
            }
            return isValid
        }

        private suspend fun readResetDate(
            startElapsed: Long,
            targetIdentity: String,
        ): Long? =
            simUsageRepository
                .getSimUsage(targetIdentity)
                .firstWithTimeout(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds)
                ?.lastResetDate
                ?: run {
                    updateStatusOnError(
                        startElapsed,
                        "Timeout reading simUsage",
                        code = TrackingStatus.ErrorCode.TIMEOUT,
                    )
                    null
                }

        /**
         * Reads the baseline retry count for the given [targetIdentity].
         *
         * @return [Result.success] containing `null` if no retry count is set,
         * [Result.success] containing `Int` if a retry count is present,
         * or [Result.failure] if reading from DataStore failed.
         */
        private suspend fun readBaselineRetryCount(targetIdentity: String): Result<Int?> =
            baselineManager.getRetryCount(targetIdentity)

        private suspend fun handleTrafficUpdateExecution(
            startElapsed: Long,
            target: TargetInfo,
            targetIdentity: String,
            currentRetryCount: Int?,
            statsResult: StatsResult,
            expectedLastResetDate: Long,
        ): TrafficUpdateOutcome =
            if (currentRetryCount != null) {
                if (currentRetryCount >= TrafficBaselineManager.MAX_BASELINE_RETRIES) {
                    baselineManager.removeRetry(targetIdentity)
                    updateStatusOnError(
                        startElapsed,
                        "Baseline reset failed for $targetIdentity after all retries",
                        type = TrackingStatus.ErrorType.DATABASE_ERROR,
                    )
                    TrafficUpdateOutcome.Failed(TrackingStatus.ErrorType.DATABASE_ERROR, null)
                } else {
                    handleRetryAction(
                        startElapsed,
                        target,
                        targetIdentity,
                        currentRetryCount,
                        statsResult,
                        expectedLastResetDate,
                    )
                }
            } else {
                executeUpdateAndBaseline(
                    startElapsed,
                    target,
                    targetIdentity,
                    statsResult,
                    expectedLastResetDate,
                )
            }

        private suspend fun executeUpdateAndBaseline(
            startElapsed: Long,
            target: TargetInfo,
            targetIdentity: String,
            statsResult: StatsResult,
            expectedLastResetDate: Long,
        ): TrafficUpdateOutcome {
            if (statsResult !is StatsResult.Success) {
                val message =
                    if (statsResult is StatsResult.Error) {
                        statsResult.exception.message
                    } else {
                        "Stats unavailable"
                    }
                updateStatusOnError(startElapsed, message)
                return TrafficUpdateOutcome.Failed(TrackingStatus.ErrorType.UNKNOWN, null)
            }
            val lastTotalBytes = fetchLastTotalBytes(targetIdentity, statsResult)
            return if (lastTotalBytes == null) {
                updateStatusOnError(
                    startElapsed,
                    "Timeout reading lastTotalBytes",
                    code = TrackingStatus.ErrorCode.TIMEOUT,
                )
                TrafficUpdateOutcome.TimedOut(startElapsed)
            } else {
                performUpdateWrite(
                    startElapsed,
                    target,
                    targetIdentity,
                    statsResult.bytes,
                    lastTotalBytes,
                    expectedLastResetDate,
                )
            }
        }

        private suspend fun performUpdateWrite(
            startElapsed: Long,
            target: TargetInfo,
            targetIdentity: String,
            currentBytes: Long,
            lastTotalBytes: Long,
            expectedLastResetDate: Long,
        ): TrafficUpdateOutcome {
            val result =
                withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                    deltaCalculator.updateTrafficBaselineAndUsage(
                        targetIdentity,
                        currentBytes,
                        lastTotalBytes,
                        target,
                        expectedLastResetDate,
                    )
                }

            if (result == null) {
                AppLogger.e("TrafficTracker: Traffic update write timed out for $targetIdentity")
                updateStatusOnError(
                    startElapsed,
                    "Traffic update write timeout",
                    code = TrackingStatus.ErrorCode.TIMEOUT,
                )
                return TrafficUpdateOutcome.TimedOut(startElapsed)
            }

            lastSuccessElapsed.set(timeProvider.elapsedRealtime())
            transitionStatus(TrackingStatus.Active, force = true)
            simChangeHandler.updateLastDataSubId(target.dataSubId)
            dataChangeNotifier.notifyDataChanged(updateWidget = true, force = false)
            return if (!result) {
                AppLogger.d(
                    "TrafficTracker: Traffic update write skipped due to window mismatch " +
                        "for $targetIdentity",
                )
                TrafficUpdateOutcome.Skipped
            } else {
                TrafficUpdateOutcome.Completed
            }
        }

        private suspend fun handleRetryAction(
            startElapsed: Long,
            target: TargetInfo,
            targetIdentity: String,
            currentPending: Int,
            statsResult: StatsResult,
            expectedLastResetDate: Long,
        ): TrafficUpdateOutcome =
            if (statsResult is StatsResult.Success) {
                handleSuccessRetryAction(
                    startElapsed,
                    target,
                    targetIdentity,
                    statsResult,
                    expectedLastResetDate,
                )
            } else {
                handleFailedRetryAction(
                    startElapsed,
                    targetIdentity,
                    currentPending,
                    statsResult,
                )
            }

    private suspend fun handleSuccessRetryAction(
        startElapsed: Long,
        target: TargetInfo,
        targetIdentity: String,
        statsResult: StatsResult.Success,
        expectedLastResetDate: Long,
    ): TrafficUpdateOutcome {
        val applied =
            withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                simUsageResetRepository.setLastTotalBytes(
                    targetIdentity,
                    statsResult.bytes,
                    expectedLastResetDate,
                )
            }
        if (applied == null) {
            updateStatusOnError(
                startElapsed,
                "Baseline write timeout on retry",
                code = TrackingStatus.ErrorCode.TIMEOUT,
            )
            return TrafficUpdateOutcome.TimedOut(startElapsed)
        }
        baselineManager.removeRetry(targetIdentity)
        lastSuccessElapsed.set(timeProvider.elapsedRealtime())
        transitionStatus(TrackingStatus.Active, force = true)
        simChangeHandler.updateLastDataSubId(target.dataSubId)
        dataChangeNotifier.notifyDataChanged(updateWidget = true, force = false)
        return if (applied) {
            AppLogger.d("Traffic baseline reset on retry for $targetIdentity to ${statsResult.bytes}")
            TrafficUpdateOutcome.Completed
        } else {
            AppLogger.d(
                "TrafficTracker: retry baseline set skipped due to window mismatch " +
                    "for $targetIdentity",
            )
            TrafficUpdateOutcome.Skipped
        }
    }

    private suspend fun handleFailedRetryAction(
        startElapsed: Long,
        targetIdentity: String,
        currentPending: Int,
        statsResult: StatsResult,
    ): TrafficUpdateOutcome {
        val nextRetry = currentPending + 1
        return if (nextRetry >= TrafficBaselineManager.MAX_BASELINE_RETRIES) {
            baselineManager.removeRetry(targetIdentity)
            updateStatusOnError(
                startElapsed,
                "Baseline reset failed for $targetIdentity after all retries",
                type = TrackingStatus.ErrorType.DATABASE_ERROR,
            )
            TrafficUpdateOutcome.Failed(TrackingStatus.ErrorType.DATABASE_ERROR, null)
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
            TrafficUpdateOutcome.Failed(TrackingStatus.ErrorType.UNKNOWN, null)
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

            /** Maximum consecutive observer retry attempts before failing with [TrackingStatus.Error]. */
            private const val MAX_OBSERVER_RETRIES = 10

            /** Base delay in milliseconds for observer exponential backoff retry. */
            private const val OBSERVER_RETRY_BASE_MS = 1000L

            /** Maximum delay in milliseconds for observer exponential backoff retry. */
            private const val OBSERVER_RETRY_MAX_MS = 30000L
        }
    }
