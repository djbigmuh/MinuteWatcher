package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.SimDiscoveryResult
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
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
        appSettingsRepository: AppSettingsRepository,
        private val mockSimProvider: MockSimProvider,
        private val fetcher: ActiveSimsFetcher,
        @ApplicationScope private val scope: CoroutineScope,
        private val cache: SimIdentityCache,
        private val permissionStateNotifier: PermissionStateNotifier,
    ) : TrackingStatusProvider {
    override val key: String = "sim_discovery"

    private val _status = MutableStateFlow<TrackingStatus>(TrackingStatus.Idle)
    override val status: StateFlow<TrackingStatus> = _status.asStateFlow()

    private val generalAttempt = AtomicLong(0)
    private val securityAttempt = AtomicLong(0)
    private val manualTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override suspend fun recover() {
        generalAttempt.set(0)
        securityAttempt.set(0)
        _status.value = TrackingStatus.Idle
        manualTrigger.emit(Unit)
    }

        val seedFlow: Flow<SeedResult> =
            flow {
                val attempt = AtomicLong(0)
                var exhausted = false
                emitAll(
                    merge(
                        flow {
                            val initial =
                                withTimeoutOrNull(SEED_TOTAL_TIMEOUT_MS.milliseconds) {
                                    val current = seedRepository.seedFlow.first()
                                    if (current.isNotEmpty()) {
                                        SeedResult.Real(current)
                                    } else {
                                        val generated = generateSeedWithRetry()
                                        if (generated != null) SeedResult.Real(generated) else SeedResult.Fallback
                                    }
                                } ?: SeedResult.Fallback
                            if (initial is SeedResult.Fallback) {
                                AppLogger.e(
                                    "SimIdentityObserver: seed unavailable after timeout, emitting Fallback. " +
                                        "SIM identity resolution will fail until seed is available.",
                                )
                            }
                            emit(initial)
                        },
                        seedRepository.seedFlow
                            .filter { it.isNotEmpty() }
                            .map { SeedResult.Real(it) as SeedResult },
                    ).onEach { attempt.set(0) }
                        .retryWhen { cause, _ ->
                            if (cause is CancellationException) return@retryWhen false
                            val currentAttempt = attempt.incrementAndGet()
                            if (currentAttempt > MAX_SEED_RETRIES) {
                                AppLogger.e("SimIdentityObserver: seedFlow max retries exceeded, falling back", cause)
                                exhausted = true
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
                        },
                )
                if (exhausted) {
                    emit(SeedResult.Fallback)
                }
            }.distinctUntilChanged()
                .shareIn(
                    scope,
                    SharingStarted.WhileSubscribed(Constants.Flow.WHILE_SUBSCRIBED_STOP_TIMEOUT_MS),
                    replay = 1,
                )

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private val physicalActiveSims: Flow<List<ActiveSimInfo>> =
        seedFlow
            .flatMapLatest { seed ->
                if (seed is SeedResult.Fallback) {
                    flow {
                        delay(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds)
                        emit(emptyList())
                    }
                } else {
                    merge(
                        discovery.observeSubscriptionChanges(),
                        manualTrigger,
                    ).onEach {
                        generalAttempt.set(0)
                        securityAttempt.set(0)
                    }.debounce(Constants.Flow.DEBOUNCE_MS.milliseconds)
                        .flatMapLatest {
                            flow {
                                val result = fetcher.fetchActiveSimsWithRetry(seed)
                                handleDiscoveryResult(result)
                            }.retryWhen { cause, _ ->
                                if (cause is CancellationException) return@retryWhen false

                                val currentAttempt = generalAttempt.incrementAndGet()
                                if (currentAttempt > MAX_GENERAL_ATTEMPTS) {
                                    AppLogger.e(
                                        "SimIdentityObserver: physicalActiveSims discovery stopped " +
                                            "after $currentAttempt consecutive failures",
                                        cause,
                                    )
                                    _status.value =
                                        TrackingStatus.Error(
                                            TrackingStatus.ErrorType.UNKNOWN,
                                            "SIM discovery stopped after $currentAttempt consecutive failures",
                                        )
                                    emit(emptyList())
                                    return@retryWhen false
                                }

                                val backoff =
                                    INITIAL_BACKOFF_MS * (1 shl minOf(currentAttempt.toInt(), MAX_BACKOFF_EXPONENT))
                                AppLogger.e(
                                    "SimIdentityObserver: physicalActiveSims discovery failure " +
                                        "(attempt $currentAttempt), restarting in ${backoff}ms",
                                    cause,
                                )
                                emit(emptyList())
                                delay(backoff.milliseconds)
                                true
                            }.catch { cause ->
                                if (cause !is IOException) {
                                    throw cause
                                }
                            }
                        }
                }
            }.distinctUntilChanged()

    private suspend fun kotlinx.coroutines.flow.FlowCollector<List<ActiveSimInfo>>.handleDiscoveryResult(
        result: SimDiscoveryResult,
    ) {
        when (result) {
            is SimDiscoveryResult.Available -> {
                generalAttempt.set(0)
                securityAttempt.set(0)
                _status.value = TrackingStatus.Active
                emit(result.sims)
            }

            is SimDiscoveryResult.PermissionMissing -> {
                val current = securityAttempt.incrementAndGet()
                if (current > MAX_SECURITY_ATTEMPTS) {
                    _status.value = TrackingStatus.Error(
                        TrackingStatus.ErrorType.PERMISSION_MISSING,
                        "SecurityException limit reached",
                    )
                    emit(emptyList())
                } else {
                    _status.value = TrackingStatus.Error(
                        TrackingStatus.ErrorType.PERMISSION_MISSING,
                        "Permission missing (attempt $current)",
                    )
                    emit(emptyList())
                    permissionStateNotifier.changes.first()
                    generalAttempt.set(0)
                    throw IOException("Permission missing, retrying after grant")
                }
            }

            is SimDiscoveryResult.Unsupported -> {
                _status.value = TrackingStatus.Error(
                    TrackingStatus.ErrorType.UNKNOWN,
                    "SIM discovery unsupported",
                )
                emit(emptyList())
            }

            is SimDiscoveryResult.Failed -> {
                throw IOException("SIM discovery failed: ${result.reason}")
            }
        }
    }

        @OptIn(ExperimentalCoroutinesApi::class)
        val activeSimsCache: StateFlow<List<ActiveSimInfo>> =
            combine(
                physicalActiveSims,
                appSettingsRepository
                    .observeAppSettings()
                    .map { it.benchmarkSimCount }
                    .distinctUntilChanged(),
            ) { physical, benchmarkSimCount ->
                val mocks = mockSimProvider.getMockSims(benchmarkSimCount)
                mocks ?: physical
            }.distinctUntilChanged()
                .onEach { sims ->
                    val activeSlots = sims.asSequence().map { it.slotIndex }.toSet()
                    cache.cleanupManual(activeSlots)
                }.stateIn(
                    scope,
                    // Eagerly: поток критичен для корректности UI, остановка недопустима.
                    SharingStarted.Eagerly,
                    emptyList(),
                )

        private suspend fun generateSeedWithRetry(): String? =
            withTimeoutOrNull(Constants.Flow.SEED_GENERATION_TIMEOUT_MS.milliseconds) {
                seedRepository.getOrCreateSeed { UUID.randomUUID().toString() }
            }

        companion object {
            private const val MAX_BACKOFF_EXPONENT = 4
            private const val INITIAL_BACKOFF_MS = 1000L
            private const val MAX_SEED_BACKOFF_MS = 10000L
            private const val MAX_SEED_RETRIES = 10
            private const val MAX_GENERAL_ATTEMPTS = 20
            private const val MAX_SECURITY_ATTEMPTS = 50

            private const val SEED_TOTAL_TIMEOUT_MS =
                (Constants.Flow.SEED_GENERATION_TIMEOUT_MS + Constants.Flow.SEED_TOTAL_TIMEOUT_EXTRA_MS)
        }
    }
