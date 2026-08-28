package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
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
    ) {
        val seedFlow: Flow<SeedResult> =
            seedRepository.seedFlow
                .filter { it.isNotEmpty() }
                .map { SeedResult.Real(it) as SeedResult }
                .onStart {
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
                }.distinctUntilChanged()
                .shareIn(scope, SharingStarted.Lazily, replay = 1)

        @OptIn(ExperimentalCoroutinesApi::class)
        private val physicalActiveSims: Flow<List<ActiveSimInfo>> =
            seedFlow
                .flatMapLatest { seed ->
                    discovery
                        .observeSubscriptionChanges()
                        .map {
                            fetcher.fetchActiveSimsWithRetry(seed)
                        }.retryWhen { cause, attempt ->
                            if (cause is CancellationException) throw cause

                            when {
                                cause is SeedUnavailableException -> {
                                    AppLogger.w(
                                        "SimIdentityObserver: Seed still unavailable after " +
                                            "fetcher retries, waiting 5s (attempt $attempt)",
                                    )
                                    delay(5000.milliseconds)
                                    true
                                }

                                (cause is IOException || cause is Exception) && attempt < 3 -> {
                                    val backoff = INITIAL_BACKOFF_MS * (1 shl attempt.toInt())
                                    AppLogger.w(
                                        "SimIdentityObserver: Error fetching sims, " +
                                            "retrying in ${backoff}ms (attempt $attempt)",
                                        cause,
                                    )
                                    delay(backoff.milliseconds)
                                    true
                                }

                                else -> false
                            }
                        }
                }.catch { e ->
                    if (e is CancellationException) throw e
                    AppLogger.e("SimIdentityObserver: Terminal error in physicalActiveSims", e)
                    emit(emptyList())
                    // Не пробрасываем — позволяем потоку завершиться штатно.
                    // Восстановление возможно через подписку заново (Eagerly перезапустит при сбое).
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
            private const val INITIAL_BACKOFF_MS = 1000L
            private const val SEED_TOTAL_TIMEOUT_MS =
                (Constants.Flow.SEED_GENERATION_TIMEOUT_MS + Constants.Flow.SEED_TOTAL_TIMEOUT_EXTRA_MS)
        }
    }
