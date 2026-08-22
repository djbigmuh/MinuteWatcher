package com.example.minutewatcher.core.data

import android.telephony.SubscriptionInfo
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    constructor(
        private val discovery: SimIdentityDiscovery,
        private val persistence: SimIdentityPersistence,
        private val cache: SimIdentityCache,
        private val seedRepository: SimIdentitySeedRepository,
        appSettingsRepository: AppSettingsRepository,
        private val mockSimProvider: MockSimProvider,
        @ApplicationScope private val scope: CoroutineScope,
    ) : SimIdentityRepository {
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
        )

        private sealed interface SeedResult {
            data class Real(
                val value: String,
            ) : SeedResult

            object Fallback : SeedResult
        }

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
                .shareIn(scope, SharingStarted.Eagerly, replay = 1)

        @OptIn(ExperimentalCoroutinesApi::class)
        private val physicalActiveSims: Flow<List<ActiveSimInfo>> =
            seedFlow.flatMapLatest { seed ->
                discovery.observeSubscriptionChanges().map {
                    fetchActiveSimsWithRetry(seed)
                }
            }

        @OptIn(ExperimentalCoroutinesApi::class)
        private val activeSimsCache: StateFlow<List<ActiveSimInfo>> =
            combine(
                physicalActiveSims,
                appSettingsRepository.observeAppSettings(),
            ) { physical, settings ->
                val mocks = mockSimProvider.getMockSims(settings.benchmarkSimCount)
                mocks ?: physical
            }.stateIn(scope, SharingStarted.Eagerly, emptyList())

        init {
            activeSimsCache
                .onEach { sims ->
                    val activeSlots = sims.asSequence().map { it.slotIndex }.toSet()
                    cache.cleanupManual(activeSlots)
                }.launchIn(scope)
        }

        @Suppress("ReturnCount")
        private suspend fun fetchActiveSimsWithRetry(seed: SeedResult): List<ActiveSimInfo> {
            var retryDelay = Constants.Flow.RETRY_DELAY_MS
            val maxDelay = Constants.Flow.LONG_TIMEOUT_MS * MAX_DELAY_MULTIPLIER
            repeat(MAX_FETCH_RETRIES) { attempt ->
                try {
                    return getActiveSimsInternal(seed)
                } catch (e: IOException) {
                    if (attempt == (MAX_FETCH_RETRIES - 1)) {
                        AppLogger.e("SimIdentityRepository: Terminal DataStore error", e)
                    } else {
                        AppLogger.e("SimIdentityRepository: DataStore error, retrying", e)
                        delay(retryDelay.milliseconds)
                        retryDelay = (retryDelay * RETRY_DELAY_MULTIPLIER).coerceAtMost(maxDelay)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Failed to fetch active SIMs", e)
                    return emptyList()
                }
            }
            return emptyList()
        }

        @Suppress("ReturnCount")
        private suspend fun getActiveSimsInternal(seed: SeedResult): List<ActiveSimInfo> {
            val seedValue =
                when (seed) {
                    is SeedResult.Real -> seed.value
                    is SeedResult.Fallback -> {
                        AppLogger.e("Cannot fetch active SIMs: identity seed unavailable", null)
                        return emptyList()
                    }
                }

            val infoList = discovery.getActiveSubscriptions()
            val identities = infoList.map { SimIdentityUtils.createIdentityFromInfo(it, seedValue) }

            persistence.retainMigrated(identities.asSequence().map { it.stableId }.toSet())

            if (infoList.isEmpty()) return emptyList()

            try {
                persistence.saveSimIdentitiesInfo(identities)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Failed to save identity info for active SIMs", e)
            }

            return createActiveSimInfoList(infoList, identities)
        }

        private fun createActiveSimInfoList(
            infoList: List<SubscriptionInfo>,
            identities: List<SimIdentity>,
        ): List<ActiveSimInfo> {
            val defaultDataSubId = discovery.getDefaultDataSubscriptionId()
            val defaultVoiceSubId = discovery.getDefaultVoiceSubscriptionId()

            return infoList.zip(identities).map { (info, identity) ->
                ActiveSimInfo(
                    identity = identity,
                    slotIndex = info.simSlotIndex,
                    subscriptionId = info.subscriptionId,
                    displayName = info.displayName?.toString(),
                    carrierName = info.carrierName?.toString(),
                    mccMnc = SimIdentityUtils.getMccMnc(info),
                    isDefaultData = info.subscriptionId == defaultDataSubId,
                    isDefaultVoice = info.subscriptionId == defaultVoiceSubId,
                )
            }
        }

        override suspend fun getIdentity(slot: Int): String = getSimIdentity(slot).stableId

        override suspend fun getSimIdentity(slot: Int): SimIdentity {
            require(slot in SimConstants.SUPPORTED_SLOT_INDICES) { "Invalid slot $slot" }

            activeSimsCache.value.find { it.slotIndex == slot }?.let {
                cache.removeManual(slot)
                return it.identity
            }

            cache.getManual(slot)?.let { return it }

            val seed =
                withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                    seedFlow.firstOrNull()
                } ?: SeedResult.Fallback

            val seedValue =
                when (seed) {
                    is SeedResult.Real -> seed.value
                    is SeedResult.Fallback -> throw SeedUnavailableException(slot)
                }

            val resolved =
                discovery.getActiveSubscriptions().find { it.simSlotIndex == slot }?.let {
                    SimIdentityUtils.createIdentityFromInfo(it, seedValue)
                } ?: throw SimNotFoundException(slot)

            cache.putManual(slot, resolved)
            persistence.saveSimIdentitiesInfo(listOf(resolved))
            return resolved
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
            private const val MAX_DELAY_MULTIPLIER = 3
            private const val MAX_FETCH_RETRIES = 5
            private const val RETRY_DELAY_MULTIPLIER = 2
        }
    }
