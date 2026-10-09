package com.example.minutewatcher.core.data

import androidx.annotation.VisibleForTesting
import com.example.minutewatcher.core.android.util.SimCardMapper
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.observeWithRetry
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.SeedResolutionResult
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.SimDiscoveryFailureReason
import com.example.minutewatcher.core.model.SimDiscoveryResult
import com.example.minutewatcher.core.model.TrackingKey
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.core.model.TrackingStatusMachine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * Observer for SIM identities.
 *
 * **Security:** Seed values are secrets and must never be output to logs.
 *
 * NOTE: TooManyFunctions is suppressed: registration, debouncing and conflict
 * resolution form one observer state machine; extraction would split its
 * invariants across classes.
 */
@Singleton
@Suppress("TooManyFunctions")
internal class SimIdentityObserver
    @Inject
    constructor(
        discovery: SimIdentityDiscovery,
        private val seedRepository: SimIdentitySeedRepository,
        private val appSettingsRepository: AppSettingsRepository,
        mockSimProvider: MockSimProvider,
        fetcher: ActiveSimsFetcher,
        private val migrationService: IdentityMigrationService,
        @ApplicationScope private val scope: CoroutineScope,
        private val cache: SimIdentityCache,
    ) : TrackingStatusProvider {
        override val key: TrackingKey = TrackingKey.SIM_DISCOVERY

        private val statusMachine = TrackingStatusMachine()
        override val status: StateFlow<TrackingStatus> = statusMachine.status

        /**
         * Updates the tracker status using canonical prioritized rules.
         */
        private fun transitionStatus(
            newStatus: TrackingStatus,
            force: Boolean = false,
        ) {
            statusMachine.transition(newStatus, force)
        }

        private val generalAttempt = AtomicLong(0)
        private val isObservationInitialized = AtomicBoolean(false)
        private val manualTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        private val recoveryJob = AtomicReference<Job?>(null)

        private val lastSeedRef = AtomicReference<SeedResult?>(null)
        private val lastEphemeralSeed = AtomicReference<String?>(null)
        private val migrationTriggered = AtomicBoolean(false)

        override suspend fun recover() {
            if (!isObservationInitialized.get()) {
                AppLogger.w("SimIdentityObserver: recover() called before observation was initialized, ignoring")
                return
            }
            generalAttempt.set(0)
            transitionStatus(TrackingStatus.Idle, force = true)

            scope.launch {
                manualTrigger.emit(Unit)
            }
        }

        @VisibleForTesting
        internal fun emitManualTriggerForTest(): Boolean = manualTrigger.tryEmit(Unit)

        private fun scheduleDiscoveryRecovery(reason: String) {
            val newJob =
                scope.launch {
                    delay(DISCOVERY_RECOVERY_COOLDOWN_MS.milliseconds)
                    generalAttempt.set(0)
                    AppLogger.d("SimIdentityObserver: auto-recovery after stop ($reason)")
                    manualTrigger.emit(Unit)
                }
            recoveryJob.getAndSet(newJob)?.cancel()
        }

        private fun cancelRecovery() {
            recoveryJob.getAndSet(null)?.cancel()
        }

        private fun toSeedResult(result: SeedResolutionResult): SeedResult =
            when (result) {
                is SeedResolutionResult.Persistent -> SeedResult.Real(result.seed, isStable = true)
                is SeedResolutionResult.Ephemeral -> SeedResult.Fallback(result.seed)
            }

        /**
         * Flow of the SIM identity seed resolution result.
         *
         * [SeedResult.Fallback] is emitted only for Ephemeral seeds (storage unavailable).
         * This flow ensures that all components use the same seed for identity derivation.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        val seedFlow: Flow<SeedResult> =
            manualTrigger
                .onStart { emit(Unit) }
                .flatMapLatest {
                    val repoFlow =
                        seedRepository.seedFlow
                            .filter { it.isNotEmpty() }
                            .map { SeedResult.Real(it, isStable = true) }
                            .catch { e ->
                                if (e is CancellationException) throw e
                                AppLogger.e("SimIdentityObserver: repoFlow error", e)
                            }

                    val retryFlow =
                        flow {
                            // 1. Initial attempt to resolve seed (persistent or ephemeral).
                            var resolution = generateSeedWithRetry()
                            if (resolution is SeedResolutionResult.Ephemeral) {
                                lastEphemeralSeed.set(resolution.seed)
                            }
                            emit(toSeedResult(resolution))

                            // 2. If resolution fell back to ephemeral (storage failed),
                            // start a background loop to periodically retry persistent resolution.
                            while (resolution is SeedResolutionResult.Ephemeral) {
                                delay(DISCOVERY_RECOVERY_COOLDOWN_MS.milliseconds)
                                AppLogger.d("SimIdentityObserver: periodic persistent seed retry")
                                resolution = generateSeedWithRetry()
                                if (resolution is SeedResolutionResult.Ephemeral) {
                                    lastEphemeralSeed.set(resolution.seed)
                                }
                                emit(toSeedResult(resolution))
                            }
                        }.catch { e ->
                            if (e is CancellationException) throw e
                            AppLogger.e("SimIdentityObserver: seed resolution loop error", e)
                        }

                    // Merge both flows: reactive updates from DataStore and periodic manual retries.
                    merge(retryFlow, repoFlow)
                }.distinctUntilChanged()
                .shareIn(
                    scope,
                    SharingStarted.Eagerly,
                    replay = 1,
                )

        @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
        private val physicalActiveSims: Flow<List<ActiveSimInfo>> =
            seedFlow
                .onEach {
                    // Reset status to Idle when seed changes, clearing transient errors.
                    // This is safe to run on each seed update as it only clears non-permission errors.
                    val current = statusMachine.current
                    if (current is TrackingStatus.Error &&
                        current.type != TrackingStatus.ErrorType.PERMISSION_MISSING
                    ) {
                        transitionStatus(TrackingStatus.Idle)
                    }
                }.flatMapLatest { seed ->
                    val lastKnownGood = AtomicReference<List<ActiveSimInfo>?>(null)
                    merge(
                        discovery.observeSubscriptionChanges(),
                        manualTrigger,
                    ).debounce(Constants.Flow.DEBOUNCE_MS.milliseconds)
                        .flatMapLatest {
                            flow {
                                var shouldContinue = true
                                var loopAttempts = 0
                                while (shouldContinue && loopAttempts < MAX_DISCOVERY_LOOP_ATTEMPTS) {
                                    loopAttempts++
                                    try {
                                        val result = fetcher.fetchActiveSimsWithRetry(seed)
                                        if (result is SimDiscoveryResult.Available) {
                                            lastKnownGood.set(result.sims)
                                            checkAndTriggerMigration(seed, result.sims)
                                        }
                                        val shouldRetry = handleDiscoveryResult(result, lastKnownGood)
                                        shouldContinue = shouldRetry
                                    } catch (e: SecurityException) {
                                        AppLogger.w("Permission denied for SIM discovery: ${e.message}")
                                        transitionStatus(
                                            TrackingStatus.Error(
                                                TrackingStatus.ErrorType.PERMISSION_MISSING,
                                                "SIM permission denied",
                                            ),
                                        )
                                        shouldContinue = false
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: IOException) {
                                        throw e
                                    } catch (e: Exception) {
                                        AppLogger.e("SimIdentityObserver: fetchActiveSimsWithRetry failed", e)
                                        transitionStatus(
                                            TrackingStatus.Error(
                                                TrackingStatus.ErrorType.UNKNOWN,
                                                e.message ?: "Unknown error",
                                            ),
                                        )
                                        shouldContinue = false
                                    }
                                }

                                if (loopAttempts >= MAX_DISCOVERY_LOOP_ATTEMPTS) {
                                    AppLogger.e(
                                        "SimIdentityObserver: discovery loop safety limit reached " +
                                            "($MAX_DISCOVERY_LOOP_ATTEMPTS)",
                                    )
                                    transitionStatus(
                                        TrackingStatus.Error(
                                            TrackingStatus.ErrorType.UNKNOWN,
                                            "SIM discovery loop safety limit reached",
                                        ),
                                    )
                                }
                            }.retryWhen { cause, _ ->
                                if (cause is CancellationException) return@retryWhen false
                                if (cause !is IOException) return@retryWhen false

                                val currentAttempt = generalAttempt.incrementAndGet()
                                if (currentAttempt > MAX_GENERAL_ATTEMPTS) {
                                    AppLogger.e(
                                        "SimIdentityObserver: physicalActiveSims recovery stopped " +
                                            "after $currentAttempt consecutive IO failures",
                                        cause,
                                    )
                                    transitionStatus(
                                        TrackingStatus.Error(
                                            TrackingStatus.ErrorType.UNKNOWN,
                                            "SIM recovery stopped after $currentAttempt consecutive IO failures",
                                        ),
                                    )
                                    scheduleDiscoveryRecovery("IO_FAILURE")
                                    return@retryWhen false
                                }

                                val backoff =
                                    INITIAL_BACKOFF_MS * (1 shl minOf(currentAttempt.toInt(), MAX_BACKOFF_EXPONENT))
                                AppLogger.e(
                                    "SimIdentityObserver: physicalActiveSims IO failure " +
                                        "(attempt $currentAttempt), restarting in ${backoff}ms",
                                    cause,
                                )
                                delay(backoff.milliseconds)
                                true
                            }.catch { cause ->
                                if (cause is CancellationException) throw cause
                                if (cause is SecurityException) {
                                    AppLogger.w("SimIdentityObserver: Permission denied in flow: ${cause.message}")
                                    transitionStatus(
                                        TrackingStatus.Error(
                                            TrackingStatus.ErrorType.PERMISSION_MISSING,
                                            "SIM permission denied",
                                        ),
                                    )
                                    emit(emptyList())
                                    return@catch
                                }
                                AppLogger.e("SimIdentityObserver: physicalActiveSims flow error", cause)

                                if (cause !is IOException) {
                                    transitionStatus(
                                        TrackingStatus.Error(
                                            TrackingStatus.ErrorType.UNKNOWN,
                                            cause.message ?: "Unexpected flow error",
                                        ),
                                    )
                                }
                                emit(emptyList())
                            }
                        }
                }.distinctUntilChanged()

        private suspend fun FlowCollector<List<ActiveSimInfo>>.handleDiscoveryResult(
            result: SimDiscoveryResult,
            lastKnownGood: AtomicReference<List<ActiveSimInfo>?>,
        ): Boolean {
            var shouldRetry = false
            when (result) {
                is SimDiscoveryResult.Available -> {
                    generalAttempt.set(0)
                    cancelRecovery()
                    transitionStatus(TrackingStatus.Active, force = true)
                    emit(result.sims)
                }

                is SimDiscoveryResult.PermissionMissing -> {
                    shouldRetry = handlePermissionMissing()
                }

                is SimDiscoveryResult.Unsupported -> {
                    transitionStatus(
                        TrackingStatus.Error(
                            TrackingStatus.ErrorType.UNKNOWN,
                            "SIM discovery unsupported",
                        ),
                    )
                    emit(emptyList())
                }

                is SimDiscoveryResult.Failed -> {
                    shouldRetry = handleDiscoveryFailure(result.reason, lastKnownGood)
                }
            }
            return shouldRetry
        }

        private suspend fun FlowCollector<List<ActiveSimInfo>>.handlePermissionMissing(): Boolean {
            generalAttempt.set(0)
            transitionStatus(
                TrackingStatus.Error(
                    TrackingStatus.ErrorType.PERMISSION_MISSING,
                    "Permission missing",
                ),
            )
            emit(emptyList())
            return false
        }

        private suspend fun FlowCollector<List<ActiveSimInfo>>.handleDiscoveryFailure(
            reason: SimDiscoveryFailureReason,
            lastKnownGood: AtomicReference<List<ActiveSimInfo>?>,
        ): Boolean {
            val currentAttempt = generalAttempt.incrementAndGet()
            return if (currentAttempt > MAX_GENERAL_ATTEMPTS) {
                AppLogger.e(
                    "SimIdentityObserver: physicalActiveSims discovery stopped " +
                        "after $currentAttempt consecutive logical failures: $reason",
                )
                transitionStatus(
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.UNKNOWN,
                        "SIM discovery stopped after $currentAttempt failures: $reason",
                    ),
                )
                scheduleDiscoveryRecovery("LOGICAL_FAILURE: $reason")
                emit(emptyList())
                false
            } else {
                val backoff =
                    INITIAL_BACKOFF_MS * (1 shl minOf(currentAttempt.toInt(), MAX_BACKOFF_EXPONENT))
                AppLogger.e(
                    "SimIdentityObserver: physicalActiveSims discovery logical failure " +
                        "(attempt $currentAttempt): $reason, retrying in ${backoff}ms",
                )
                emit(lastKnownGood.get().orEmpty())
                delay(backoff.milliseconds)
                true
            }
        }

        /**
         * StateFlow of resolved active SIMs (null until the first discovery completes),
         * prioritizing mock SIMs in benchmark builds.
         */
        @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
        val resolvedActiveSimsCache: StateFlow<List<ActiveSimInfo>?> =
            combine(
                physicalActiveSims,
                observeBenchmarkSimCount(),
            ) { physical, benchmarkSimCount ->
                val mocks = mockSimProvider.getMockSims(benchmarkSimCount)
                mocks ?: physical
            }.distinctUntilChanged()
                .observeWithRetry(
                    label = "SimIdentityObserver: activeSims",
                    logThreshold = 5,
                    onPersistentFailure = { cause ->
                        transitionStatus(
                            TrackingStatus.Error(
                                TrackingStatus.ErrorType.UNKNOWN,
                                cause.message ?: "SIM observation degraded",
                            ),
                        )
                    },
                    onRecovered = {
                        transitionStatus(TrackingStatus.Active, force = true)
                    },
                    onSourceCompleted = {},
                ).onEach { cleanupCache(it) }
                .onStart { isObservationInitialized.set(true) }
                .stateIn(
                    scope,
                    SharingStarted.Eagerly,
                    null,
                )

        /**
         * StateFlow of active SIMs for consumers expecting a non-null list (defaults to emptyList()).
         */
        val activeSimsCache: StateFlow<List<ActiveSimInfo>> =
            resolvedActiveSimsCache
                .map { it ?: emptyList() }
                .stateIn(
                    scope,
                    SharingStarted.Eagerly,
                    emptyList(),
                )

        private fun cleanupCache(sims: List<ActiveSimInfo>) {
            val activeSlots = sims.asSequence().map { it.slotIndex }.toSet()
            cache.cleanupManual(activeSlots)
            SimCardMapper.cleanupSlots(activeSlots)
        }

        private fun observeBenchmarkSimCount(): Flow<Int> =
            appSettingsRepository
                .observeAppSettings()
                .map { it.benchmarkSimCount }
                .distinctUntilChanged()

        /**
         * Checks if a transition from Fallback (ephemeral) seed to Real (persistent) seed
         * has occurred and triggers identity migration if needed.
         *
         * **Migration Guard:** The migration is triggered when transitioning from Fallback to Real,
         * or if discovery emits while in Real state and a previous migration attempt failed.
         * If the system returns to Fallback state, the trigger is reset.
         */
        private fun checkAndTriggerMigration(
            currentSeed: SeedResult,
            sims: List<ActiveSimInfo>,
        ) {
            val lastSeed = lastSeedRef.getAndSet(currentSeed)
            if (currentSeed is SeedResult.Real) {
                // Trigger migration on Fallback -> Real transition, or retry if previously failed.
                val shouldTrigger = (lastSeed is SeedResult.Fallback) || !migrationTriggered.get()
                if (shouldTrigger && migrationTriggered.compareAndSet(false, true)) {
                    val fallbackSeedValue = (lastSeed as? SeedResult.Fallback)?.seed ?: lastEphemeralSeed.get()

                    if (fallbackSeedValue == null) {
                        // If there is no record of an ephemeral seed being used, there is nothing to migrate.
                        // The trigger flag remains set so no further migration checks are run
                        // until returning to Fallback state.
                        return
                    }

                    AppLogger.d("SimIdentityObserver: triggering migration from ephemeral seed to persistent seed")
                    scope.launch {
                        try {
                            val outcome = migrationService.migrateFromEphemeralSeed(fallbackSeedValue, sims)
                            AppLogger.d(
                                "SimIdentityObserver: migration from ephemeral seed finished. " +
                                    "Outcome: migrated=${outcome.migrated}, conflicts=${outcome.conflicts}, " +
                                    "failures=${outcome.failures}",
                            )
                            if (outcome.failures > 0) {
                                AppLogger.e(
                                    "SimIdentityObserver: migration had ${outcome.failures} failures, will retry",
                                )
                                // Reset trigger on failure to allow retry upon next discovery emission
                                migrationTriggered.set(false)
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            AppLogger.e("SimIdentityObserver: migration failed with unexpected error, will retry", e)
                            // Reset trigger on exception to allow retry
                            migrationTriggered.set(false)
                        }
                    }
                }
            } else if (currentSeed is SeedResult.Fallback) {
                // Track ephemeral seed and reset trigger for future migration
                lastEphemeralSeed.set(currentSeed.seed)
                migrationTriggered.set(false)
            }
        }

        /**
         * Generates seed identity with bounded timeout attempts and deterministic fallback.
         * Total execution time is bounded by approximately 2 * SEED_GENERATION_TIMEOUT_MS.
         */
        private suspend fun generateSeedWithRetry(): SeedResolutionResult {
            repeat(2) {
                try {
                    val result =
                        withTimeoutOrNull(Constants.Flow.SEED_GENERATION_TIMEOUT_MS.milliseconds) {
                            seedRepository.getOrCreateSeedOrEphemeralFallback { UUID.randomUUID().toString() }
                        }
                    if (result != null) return result
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("SimIdentityObserver: seed resolution attempt failed", e)
                }
            }
            AppLogger.e("SimIdentityObserver: seed resolution failed, using ephemeral seed")
            val ephemeralSeed =
                seedRepository.getOrCreateEphemeralSeed {
                    UUID.randomUUID().toString()
                }
            return SeedResolutionResult.Ephemeral(ephemeralSeed)
        }

        companion object {
            private const val MAX_BACKOFF_EXPONENT = 4
            private const val INITIAL_BACKOFF_MS = 1000L
            private const val MAX_GENERAL_ATTEMPTS = 5
            private const val MAX_DISCOVERY_LOOP_ATTEMPTS = 10
            private const val DISCOVERY_RECOVERY_COOLDOWN_MS = 15 * 60_000L
        }
    }
