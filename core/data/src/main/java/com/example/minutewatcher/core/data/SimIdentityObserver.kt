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
                            when (cause) {
                                is CancellationException -> throw cause
                                is SeedUnavailableException -> {
                                    if (attempt < MAX_SEED_RETRY_ATTEMPTS) {
                                        AppLogger.w(
                                            "SimIdentityObserver: Seed unavailable, retry ${attempt + 1}/$MAX_SEED_RETRY_ATTEMPTS",
                                        )
                                        delay(5000.milliseconds)
                                        true
                                    } else {
                                        AppLogger.e(
                                            "SimIdentityObserver: Seed unavailable after $MAX_SEED_RETRY_ATTEMPTS retries, stopping",
                                        )
                                        false
                                    }
                                }

                                is IOException -> {
                                    if (attempt < MAX_FETCH_RETRY_ATTEMPTS) {
                                        val backoff = INITIAL_BACKOFF_MS * (1 shl attempt.toInt())
                                        AppLogger.w(
                                            "SimIdentityObserver: Error fetching sims, " +
                                                "retrying in ${backoff}ms (attempt ${attempt + 1})",
                                            cause,
                                        )
                                        delay(backoff.milliseconds)
                                        true
                                    } else {
                                        false
                                    }
                                }

                                else -> false
                            }
                        }
                }.retryWhen { cause, attempt ->
                    (cause as? CancellationException)?.let { throw it }
                    if (cause is SecurityException) return@retryWhen false

                    if (attempt < MAX_FLOW_RETRY_ATTEMPTS) {
                        val backoff = INITIAL_BACKOFF_MS * (1 shl attempt.toInt())
                        AppLogger.w(
                            "SimIdentityObserver: Retrying physicalActiveSims flow due to error (${attempt + 1}/$MAX_FLOW_RETRY_ATTEMPTS), " +
                                "delay ${backoff}ms",
                            cause,
                        )
                        delay(backoff.milliseconds)
                        true
                    } else {
                        false
                    }
                }.catch { e ->
                    (e as? CancellationException)?.let { throw it }
                    if (e is SecurityException) {
                        AppLogger.w("SimIdentityObserver: SecurityException (permission revoked), emitting empty list")
                    } else {
                        AppLogger.e(
                            "SimIdentityObserver: Terminal error in physicalActiveSims after retries, flow terminated",
                            e,
                        )
                    }
                    emit(emptyList())
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

            /** Количество повторных попыток (не считая исходной). Итого попыток = значение + 1 */
            private const val MAX_FETCH_RETRY_ATTEMPTS = 3L

            /** Количество повторных попыток (не считая исходной). Итого попыток = значение + 1 */
            private const val MAX_SEED_RETRY_ATTEMPTS = 10L

            /** Количество повторных попыток (не считая исходной). Итого попыток = значение + 1 */
            private const val MAX_FLOW_RETRY_ATTEMPTS = 5L

            private const val SEED_TOTAL_TIMEOUT_MS =
                (Constants.Flow.SEED_GENERATION_TIMEOUT_MS + Constants.Flow.SEED_TOTAL_TIMEOUT_EXTRA_MS)
        }
    }
