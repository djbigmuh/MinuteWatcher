package com.example.minutewatcher.core.data

import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.data.util.SimIdentityUtils
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.SeedUnavailableException
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.domain.SimNotFoundException
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.Constants.SimConstants
import com.example.minutewatcher.core.model.SimIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * Implementation of [SimIdentityRepository].
 *
 * Central hub for managing SIM identities, discovery, and persistence.
 * Delegates work to specialized components:
 * - [SimIdentityDiscovery] for SubscriptionManager interactions.
 * - [SimIdentityPersistence] for DataStore and migration logic.
 * - [SimIdentityCache] for in-memory caching.
 *
 * Safe to call from any coroutine context.
 */
@Suppress("TooManyFunctions")
@Singleton
class SimIdentityRepositoryImpl
    @Inject
    internal constructor(
        private val discovery: SimIdentityDiscovery,
        private val persistence: SimIdentityPersistence,
        private val cache: SimIdentityCache,
        private val seedRepository: SimIdentitySeedRepository,
        appSettingsRepository: AppSettingsRepository,
        private val mockSimProvider: MockSimProvider,
        @ApplicationScope private val scope: CoroutineScope,
        private val fetcher: ActiveSimsFetcher,
    ) : SimIdentityRepository {
        private val activeSimsMutex = Mutex()

        @VisibleForTesting
        constructor(
            discoveryDeps: SimDiscoveryDependencies,
            dataStore: DataStore<Preferences>,
            seedRepository: SimIdentitySeedRepository,
            appSettingsRepository: AppSettingsRepository,
            migrationService: IdentityMigrationService,
            mockSimProvider: MockSimProvider,
            timeProvider: TimeProvider,
            scope: CoroutineScope,
        ) : this(
            discovery = SimIdentityDiscovery(discoveryDeps),
            persistence = SimIdentityPersistence(dataStore, migrationService),
            cache = SimIdentityCache(timeProvider),
            seedRepository = seedRepository,
            appSettingsRepository = appSettingsRepository,
            mockSimProvider = mockSimProvider,
            scope = scope,
            fetcher =
                ActiveSimsFetcher(
                    discovery = SimIdentityDiscovery(discoveryDeps),
                    persistence = SimIdentityPersistence(dataStore, migrationService),
                ),
        )

        private val seedFlow: Flow<SeedResult> =
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
                    emit(initial)
                }.distinctUntilChanged()
                .shareIn(scope, SharingStarted.Lazily, replay = 1)

        @OptIn(ExperimentalCoroutinesApi::class)
        private val physicalActiveSims: Flow<List<ActiveSimInfo>> =
            seedFlow
                .flatMapLatest { seed ->
                    discovery.observeSubscriptionChanges().map {
                        fetcher.fetchActiveSimsWithRetry(seed)
                    }
                }.catch { e ->
                    // Пустой список в этом случае означает деградацию хранилища, а не физическое отсутствие SIM.
                    AppLogger.e("SimIdentityRepository: Terminal error in physicalActiveSims stream", e)
                    emit(emptyList())
                }

        @OptIn(ExperimentalCoroutinesApi::class)
        private val activeSimsCache: StateFlow<List<ActiveSimInfo>> =
            combine(
                physicalActiveSims,
                appSettingsRepository.observeAppSettings(),
            ) { physical, settings ->
                val mocks = mockSimProvider.getMockSims(settings.benchmarkSimCount)
                mocks ?: physical
            }.distinctUntilChanged()
                .stateIn(
                    scope,
                    SharingStarted.WhileSubscribed(Constants.Flow.WHILE_SUBSCRIBED_STOP_TIMEOUT_MS),
                    emptyList(),
                )

        init {
            activeSimsCache
                .onEach { sims ->
                    val activeSlots = sims.asSequence().map { it.slotIndex }.toSet()
                    cache.cleanupManual(activeSlots)
                }.launchIn(scope)
        }

        override suspend fun getIdentity(slot: Int): String = getSimIdentity(slot).stableId

        override suspend fun getSimIdentity(slot: Int): SimIdentity {
            require(slot in SimConstants.SUPPORTED_SLOT_INDICES) { "Invalid slot $slot" }

            // 1. Initial fast check under lock
            activeSimsMutex.withLock {
                activeSimsCache.value.find { it.slotIndex == slot }?.let {
                    cache.removeManual(slot)
                    return it.identity
                }
                cache.getManual(slot)?.let { return it }
            }

            // 2. Perform slow operations outside the lock
            val identity =
                withTimeoutOrNull(Constants.Flow.LONG_TIMEOUT_MS.milliseconds) {
                    val seed =
                        withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                            seedFlow.firstOrNull()
                        } ?: SeedResult.Fallback

                    val seedValue =
                        when (seed) {
                            is SeedResult.Real -> seed.value
                            is SeedResult.Fallback -> throw SeedUnavailableException(slot)
                        }

                    discovery.getActiveSubscriptions().find { it.simSlotIndex == slot }?.let {
                        SimIdentityUtils.createIdentityFromInfo(it, seedValue)
                    } ?: throw SimNotFoundException(slot)
                } ?: throw IOException("Timeout resolving identity for slot $slot")

            // 3. Update cache and persistence under lock
            return activeSimsMutex.withLock {
                // Re-check if someone else resolved it while we were busy
                activeSimsCache.value.find { it.slotIndex == slot }?.let {
                    cache.removeManual(slot)
                    return@withLock it.identity
                }
                cache.getManual(slot)?.let { return@withLock it }

                cache.putManual(slot, identity)
                persistence.saveSimIdentitiesInfo(listOf(identity))
                identity
            }
        }

        override fun observeActiveSims(): Flow<List<ActiveSimInfo>> = activeSimsCache

        override fun getActiveIdentities(): Flow<List<String>> =
            observeActiveSims().map { sims -> sims.map { it.identity.stableId } }.distinctUntilChanged()

        @OptIn(FlowPreview::class)
        override fun observeIdentities(): Flow<List<String>> =
            getActiveIdentities().debounce(Constants.Flow.DEBOUNCE_MS.milliseconds)

        override fun getSimOperator(identity: String): Flow<String> = persistence.getSimOperator(identity)

        override fun getCustomSimOperator(identity: String): Flow<String?> = persistence.getCustomSimOperator(identity)

        override suspend fun setSimOperator(
            identity: String,
            name: String,
        ) = persistence.setSimOperator(identity, name)

        override suspend fun setCustomSimOperator(
            identity: String,
            name: String?,
        ) = persistence.setCustomSimOperator(identity, name)

        override fun getSimCode(identity: String): Flow<String> = persistence.getSimCode(identity)

        override suspend fun setSimCode(
            identity: String,
            code: String,
        ) = persistence.setSimCode(identity, code)

        private suspend fun generateSeedWithRetry(): String? =
            withTimeoutOrNull(Constants.Flow.SEED_GENERATION_TIMEOUT_MS.milliseconds) {
                seedRepository.getOrCreateSeed { UUID.randomUUID().toString() }
            }

        companion object {
            private const val SEED_TOTAL_TIMEOUT_MS =
                (Constants.Flow.SEED_GENERATION_TIMEOUT_MS + Constants.Flow.SEED_TOTAL_TIMEOUT_EXTRA_MS)
        }
    }
