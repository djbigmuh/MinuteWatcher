package com.example.minutewatcher.core.data

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
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
        @ApplicationScope private val scope: CoroutineScope,
        private val cache: SimIdentityCache,
    ) : TrackingStatusProvider {
    override val key: String = "sim_discovery"

    private val _status = MutableStateFlow<TrackingStatus>(TrackingStatus.Idle)
    override val status: StateFlow<TrackingStatus> = _status.asStateFlow()

    private val generalAttempt = AtomicLong(0)
    private val seedAttempt = AtomicLong(0)
    private val realEmitted = AtomicBoolean(false)
    private val isObservationInitialized = AtomicBoolean(false)
    private val manualTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override suspend fun recover() {
        if (!isObservationInitialized.get()) {
            AppLogger.w("SimIdentityObserver: recover() called before observation was initialized, ignoring")
            return
        }
        generalAttempt.set(0)
        seedAttempt.set(0)
        realEmitted.set(false)
        _status.value = TrackingStatus.Idle
        manualTrigger.emit(Unit)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val seedFlow: Flow<SeedResult> =
        manualTrigger
            .onStart { emit(Unit) }
            .flatMapLatest {
                flow<SeedResult> {
                    seedAttempt.set(0)
                    emitAll(
                        merge<SeedResult>(
                            flow {
                                // Попытка первичной генерации или получения сида с основным таймаутом.
                                val primaryAttempt =
                                    withTimeoutOrNull(Constants.Flow.SEED_GENERATION_TIMEOUT_MS.milliseconds) {
                                        val current =
                                            seedRepository.seedFlow
                                                .filter { it.isNotEmpty() }
                                                .firstOrNull()
                                        if (current != null) {
                                            SeedResult.Real(current, isStable = true)
                                        } else {
                                            val generated = generateSeedWithRetry()
                                            SeedResult.Real(
                                                value = generated.seed,
                                                isStable = generated is SeedResolutionResult.Persistent,
                                            )
                                        }
                                    }

                                val finalResult =
                                    when {
                                        primaryAttempt is SeedResult.Real -> primaryAttempt
                                        realEmitted.get() -> null // Уже обработано параллельным потоком
                                        else -> {
                                            // Основная попытка не удалась или истек таймаут.
                                            val secondaryAttempt =
                                                withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                                                    seedRepository.seedFlow
                                                        .filter { it.isNotEmpty() }
                                                        .firstOrNull()
                                                }
                                            if (secondaryAttempt != null) {
                                                SeedResult.Real(secondaryAttempt, isStable = true)
                                            } else {
                                                // Даже если не удалось получить стабильный сид, используем временный,
                                                // чтобы не блокировать обнаружение SIM.
                                                SeedResult.Real(UUID.randomUUID().toString(), isStable = false)
                                            }
                                        }
                                    }

                                if (finalResult is SeedResult.Real) {
                                    if (realEmitted.compareAndSet(false, true)) {
                                        emit(finalResult)
                                    }
                                }
                            },
                            seedRepository.seedFlow
                                .filter { it.isNotEmpty() }
                                .onEach { realEmitted.set(true) }
                                .map { SeedResult.Real(it, isStable = true) },
                        ).onEach {
                            seedAttempt.set(0)
                        }.retryWhen { cause, _ ->
                            if (cause is CancellationException) return@retryWhen false
                            val currentAttempt = seedAttempt.incrementAndGet()
                            if (currentAttempt > MAX_SEED_RETRIES) {
                                AppLogger.e("SimIdentityObserver: seedFlow max retries exceeded, using ephemeral seed")
                                return@retryWhen false
                            }
                            val backoff = (INITIAL_BACKOFF_MS * currentAttempt).coerceAtMost(MAX_SEED_BACKOFF_MS)
                            AppLogger.e(
                                "SimIdentityObserver: seedFlow failure (attempt $currentAttempt), " +
                                    "retrying in ${backoff}ms",
                                cause,
                            )
                            delay(backoff.milliseconds)
                            true
                        }.catch { cause ->
                            if (cause is CancellationException) throw cause
                            AppLogger.e("SimIdentityObserver: seedFlow failed, using ephemeral fallback", cause)
                            if (!realEmitted.get()) emit(
                                SeedResult.Real(
                                    UUID.randomUUID().toString(),
                                    isStable = false
                                )
                            )
                        },
                    )
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
            .flatMapLatest { seed ->
                if (_status.value is TrackingStatus.Error &&
                    (_status.value as TrackingStatus.Error).message == "SIM identity seed unavailable"
                ) {
                    _status.value = TrackingStatus.Idle
                }
                merge(
                    discovery.observeSubscriptionChanges(),
                    manualTrigger,
                ).debounce(Constants.Flow.DEBOUNCE_MS.milliseconds)
                        .flatMapLatest {
                            flow {
                                var shouldContinue = true
                                while (shouldContinue) {
                                    try {
                                        val result = fetcher.fetchActiveSimsWithRetry(seed)
                                        val shouldRetry = handleDiscoveryResult(result)
                                        shouldContinue = shouldRetry
                                    } catch (e: SecurityException) {
                                        AppLogger.w("Permission denied for SIM discovery: ${e.message}")
                                        _status.value =
                                            TrackingStatus.Error(
                                                TrackingStatus.ErrorType.PERMISSION_MISSING,
                                                "SIM permission denied",
                                            )
                                        shouldContinue = false
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: IOException) {
                                        throw e
                                    } catch (e: Exception) {
                                        AppLogger.e("SimIdentityObserver: fetchActiveSimsWithRetry failed", e)
                                        _status.value =
                                            TrackingStatus.Error(
                                                TrackingStatus.ErrorType.UNKNOWN,
                                                e.message ?: "Unknown error",
                                            )
                                        shouldContinue = false
                                    }
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
                                    _status.value =
                                        TrackingStatus.Error(
                                            TrackingStatus.ErrorType.UNKNOWN,
                                            "SIM recovery stopped after $currentAttempt consecutive IO failures",
                                        )
                                    emit(emptyList())
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
                                AppLogger.e("SimIdentityObserver: physicalActiveSims flow error", cause)

                                if (cause !is IOException) {
                                    _status.value =
                                        TrackingStatus.Error(
                                            TrackingStatus.ErrorType.UNKNOWN,
                                            cause.message ?: "Unexpected flow error",
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
                _status.value = TrackingStatus.Active
                emit(result.sims)
            }

            is SimDiscoveryResult.PermissionMissing -> {
                shouldRetry = handlePermissionMissing()
            }

            is SimDiscoveryResult.Unsupported -> {
                _status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.UNKNOWN,
                        "SIM discovery unsupported",
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
        _status.value =
            TrackingStatus.Error(
                TrackingStatus.ErrorType.PERMISSION_MISSING,
                "Permission missing",
            )
        emit(emptyList())
        return false
    }

    private suspend fun FlowCollector<List<ActiveSimInfo>>.handleDiscoveryFailure(
        reason: SimDiscoveryFailureReason,
    ): Boolean {
        val currentAttempt = generalAttempt.incrementAndGet()
        return if (currentAttempt > MAX_GENERAL_ATTEMPTS) {
            AppLogger.e(
                "SimIdentityObserver: physicalActiveSims discovery stopped " +
                    "after $currentAttempt consecutive logical failures: $reason",
            )
            _status.value =
                TrackingStatus.Error(
                    TrackingStatus.ErrorType.UNKNOWN,
                    "SIM discovery stopped after $currentAttempt failures: $reason",
                )
            emit(emptyList())
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
                    mocks != null -> mocks
                    else -> physical
                }
            }.debounce(50.milliseconds)
                .distinctUntilChanged()
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
    }

    private fun observeBenchmarkSimCount(): Flow<Int> =
        appSettingsRepository
            .observeAppSettings()
            .map { it.benchmarkSimCount }
            .distinctUntilChanged()

    private suspend fun generateSeedWithRetry(): SeedResolutionResult =
            withTimeoutOrNull(Constants.Flow.SEED_GENERATION_TIMEOUT_MS.milliseconds) {
                seedRepository.getOrCreateSeedOrEphemeralFallback { UUID.randomUUID().toString() }
            } ?: SeedResolutionResult.Ephemeral(UUID.randomUUID().toString())

        companion object {
            private const val MAX_BACKOFF_EXPONENT = 4
            private const val INITIAL_BACKOFF_MS = 1000L
            private const val MAX_SEED_BACKOFF_MS = 10000L
            private const val MAX_SEED_RETRIES = 10
            private const val MAX_GENERAL_ATTEMPTS = 20
        }
    }
