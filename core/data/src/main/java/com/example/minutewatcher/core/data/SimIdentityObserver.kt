package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.android.util.SimCardMapper
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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

@Singleton
internal class SimIdentityObserver
    @Inject
    constructor(
        private val discovery: SimIdentityDiscovery,
        private val seedRepository: SimIdentitySeedRepository,
        private val appSettingsRepository: AppSettingsRepository,
        private val mockSimProvider: MockSimProvider,
        private val fetcher: ActiveSimsFetcher,
        private val migrationService: IdentityMigrationService,
        @ApplicationScope private val scope: CoroutineScope,
        private val cache: SimIdentityCache,
    ) : TrackingStatusProvider {
        override val key: TrackingKey = TrackingKey.SIM_DISCOVERY

        private val _status = MutableStateFlow<TrackingStatus>(TrackingStatus.Idle)
        override val status: StateFlow<TrackingStatus> = _status.asStateFlow()

        /**
         * Updates the tracker status using a prioritized transition rule to prevent race conditions.
         * Priority: PERMISSION_MISSING > DATABASE_ERROR > UNKNOWN (Error) > Active = Idle.
         */
        private fun transitionStatus(
            newStatus: TrackingStatus,
            force: Boolean = false,
        ) {
            _status.update { current ->
                val currentPrio = getStatusPriority(current)
                val newPrio = getStatusPriority(newStatus)

                when {
                    force -> newStatus
                    current == newStatus -> current
                    newPrio >= currentPrio -> newStatus
                    // Recovery: Success can clear transient UNKNOWN error
                    newPrio == TrackingStatus.PRIORITY_HEALTHY &&
                        currentPrio == TrackingStatus.PRIORITY_TRANSIENT_ERROR -> newStatus

                    else -> current
                }
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

                TrackingStatus.Active, TrackingStatus.Idle -> TrackingStatus.PRIORITY_HEALTHY
                else -> 0
            }

        private val generalAttempt = AtomicLong(0)
        private val isObservationInitialized = AtomicBoolean(false)
        private val manualTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        private val lastSeedRef = AtomicReference<SeedResult?>(null)
        private val migrationTriggered = AtomicBoolean(false)

        init {
            scope.launch {
                try {
                    val initialSeed = seedRepository.seedFlow.first()
                    if (initialSeed.isEmpty()) {
                        AppLogger.d("SimIdentityObserver: Initial seed empty, triggering generation")
                        generateSeedWithRetry()
                    }
                } catch (e: Exception) {
                    AppLogger.e("SimIdentityObserver: Failed to check/generate initial seed", e)
                }
            }
        }

        override suspend fun recover() {
            if (!isObservationInitialized.get()) {
                AppLogger.w("SimIdentityObserver: recover() called before observation was initialized, ignoring")
                return
            }
            generalAttempt.set(0)
            transitionStatus(TrackingStatus.Idle, force = true)

            // Trigger seed re-generation/check on recovery
            scope.launch {
                generateSeedWithRetry()
                manualTrigger.emit(Unit)
            }
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        val seedFlow: Flow<SeedResult> =
            manualTrigger
                .onStart { emit(Unit) }
                .flatMapLatest {
                    flow {
                        // Ждем первого значения из репозитория.
                        // В DataStore оно всегда есть (хотя бы пустая строка).
                        // Используем таймаут для защиты от "зависшего" DataStore.
                        val initialSeed =
                            withTimeoutOrNull(Constants.Flow.SEED_GENERATION_TIMEOUT_MS.milliseconds) {
                                seedRepository.seedFlow.first()
                            }

                        when {
                            initialSeed == null || initialSeed.isEmpty() -> {
                                // First attempt to generate a real seed before falling back
                                val generationResult = generateSeedWithRetry()
                                when (generationResult) {
                                    is SeedResolutionResult.Persistent -> {
                                        emit(SeedResult.Real(generationResult.seed, isStable = true))
                                    }
                                    is SeedResolutionResult.Ephemeral -> {
                                        emit(SeedResult.Fallback(generationResult.seed))
                                    }
                                }
                            }

                            else -> {
                                // Сид есть — используем его
                                emit(SeedResult.Real(initialSeed, isStable = true))
                            }
                        }

                        // Подписка на будущие изменения
                        emitAll(
                            seedRepository.seedFlow
                                .filter { it.isNotEmpty() }
                                .map { SeedResult.Real(it, isStable = true) },
                        )
                    }.catch { e ->
                        AppLogger.e("SimIdentityObserver: seedFlow source error", e)
                        val fallbackSeedResult =
                            try {
                                seedRepository.getOrCreateSeedOrEphemeralFallback {
                                    UUID.randomUUID().toString()
                                }
                            } catch (inner: Exception) {
                                SeedResolutionResult.Ephemeral(UUID.randomUUID().toString())
                            } ?: SeedResolutionResult.Ephemeral(UUID.randomUUID().toString())
                        emit(SeedResult.Fallback(fallbackSeedResult.seed))
                    }
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
                    _status.update { current ->
                        if (current is TrackingStatus.Error &&
                            current.type != TrackingStatus.ErrorType.PERMISSION_MISSING
                        ) {
                            TrackingStatus.Idle
                        } else {
                            current
                        }
                    }
                }.flatMapLatest { seed ->
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
                                            checkAndTriggerMigration(seed, result.sims)
                                        }
                                        val shouldRetry = handleDiscoveryResult(result)
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
        ): Boolean {
            var shouldRetry = false
            when (result) {
                is SimDiscoveryResult.Available -> {
                    transitionStatus(TrackingStatus.Active)
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
                    shouldRetry = handleDiscoveryFailure(result.reason)
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
        ): Boolean {
            val currentAttempt = generalAttempt.incrementAndGet()
            return if (currentAttempt > MAX_GENERAL_ATTEMPTS) {
                generalAttempt.set(0)
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
                false
            } else {
                val backoff =
                    INITIAL_BACKOFF_MS * (1 shl minOf(currentAttempt.toInt(), MAX_BACKOFF_EXPONENT))
                AppLogger.e(
                    "SimIdentityObserver: physicalActiveSims discovery logical failure " +
                        "(attempt $currentAttempt): $reason, retrying in ${backoff}ms",
                )
                emit(emptyList())
                delay(backoff.milliseconds)
                true
            }
        }

        @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
        val activeSimsCache: StateFlow<List<ActiveSimInfo>> =
            combine(
                physicalActiveSims,
                observeBenchmarkSimCount(),
            ) { physical, benchmarkSimCount ->
                val mocks = mockSimProvider.getMockSims(benchmarkSimCount)

                when {
                    physical.isNotEmpty() -> physical
                    mocks != null -> mocks
                    else -> physical
                }
            }.distinctUntilChanged()
                .onEach { cleanupCache(it) }
                .onStart { isObservationInitialized.set(true) }
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

        private fun checkAndTriggerMigration(
            currentSeed: SeedResult,
            sims: List<ActiveSimInfo>,
        ) {
            val lastSeed = lastSeedRef.getAndSet(currentSeed)
            if (lastSeed is SeedResult.Fallback && currentSeed is SeedResult.Real) {
                if (migrationTriggered.compareAndSet(false, true)) {
                    AppLogger.d("SimIdentityObserver: detecting Fallback -> Real transition, triggering migration")
                    scope.launch {
                        try {
                            val outcome = migrationService.migrateFromEphemeralSeed(lastSeed.seed, sims)
                            AppLogger.d(
                                "SimIdentityObserver: migration from ephemeral seed finished. " +
                                    "Outcome: migrated=${outcome.migrated}, conflicts=${outcome.conflicts}, " +
                                    "failures=${outcome.failures}",
                            )
                            if (outcome.failures > 0) {
                                AppLogger.e("SimIdentityObserver: migration had ${outcome.failures} failures")
                            }
                        } catch (e: Exception) {
                            AppLogger.e("SimIdentityObserver: migration failed with unexpected error", e)
                        } finally {
                            // Reset trigger if we ever go back to Fallback?
                            // TZ says "one-time (guard AtomicBoolean migrationTriggered on a pair of seed values)"
                            // but if it goes Real -> Fallback -> Real again, should we migrate again?
                            // Usually ephemeral seed is stable per process, so if we restored persistent one,
                            // we shouldn't need to migrate again from the SAME eph seed.
                            // However, if process stays alive and DataStore fails AGAIN, we might get a NEW eph seed.
                            // But TZ says "guard migrationTriggered on a pair of seed values".
                        }
                    }
                }
            } else if (currentSeed is SeedResult.Fallback) {
                // Reset trigger when we are in Fallback, so we can trigger again when we go to Real
                migrationTriggered.set(false)
            }
        }

        private suspend fun generateSeedWithRetry(): SeedResolutionResult =
            try {
                withTimeoutOrNull(Constants.Flow.SEED_GENERATION_TIMEOUT_MS.milliseconds) {
                    seedRepository.getOrCreateSeedOrEphemeralFallback { UUID.randomUUID().toString() }
                } ?: seedRepository.getOrCreateSeedOrEphemeralFallback { UUID.randomUUID().toString() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("SimIdentityObserver: Seed generation process failed", e)
                try {
                    seedRepository.getOrCreateSeedOrEphemeralFallback { UUID.randomUUID().toString() }
                } catch (inner: Exception) {
                    SeedResolutionResult.Ephemeral(UUID.randomUUID().toString())
                } ?: SeedResolutionResult.Ephemeral(UUID.randomUUID().toString())
            } ?: SeedResolutionResult.Ephemeral(UUID.randomUUID().toString())

        companion object {
            private const val MAX_BACKOFF_EXPONENT = 4
            private const val INITIAL_BACKOFF_MS = 1000L
            private const val MAX_GENERAL_ATTEMPTS = 5
            private const val MAX_DISCOVERY_LOOP_ATTEMPTS = 10
        }
    }
