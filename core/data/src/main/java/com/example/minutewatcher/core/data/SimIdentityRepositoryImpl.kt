package com.example.minutewatcher.core.data

import android.os.Build
import android.telephony.SubscriptionManager
import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.data.di.SimDataStore
import com.example.minutewatcher.core.data.util.SimIdentityUtils
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.Constants.SimConstants
import com.example.minutewatcher.core.model.IdentitySource
import com.example.minutewatcher.core.model.SimIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@Singleton
class SimIdentityRepositoryImpl
    @Inject
    constructor(
        private val discoveryDeps: SimDiscoveryDependencies,
        @SimDataStore dataStore: DataStore<Preferences>,
        private val seedRepository: SimIdentitySeedRepository,
        appSettingsRepository: AppSettingsRepository,
        private val migrationService: IdentityMigrationService,
        private val mockSimProvider: MockSimProvider,
        @ApplicationScope private val scope: CoroutineScope,
    ) : BaseDataStoreRepository(dataStore),
        SimIdentityRepository {
        private val context get() = discoveryDeps.context
        private val subscriptionManager get() = discoveryDeps.subscriptionManager
        private val permissionManager get() = discoveryDeps.permissionManager
        private val permissionStateNotifier get() = discoveryDeps.permissionStateNotifier

        private sealed interface SeedResult {
            data class Real(
                val value: String,
            ) : SeedResult

            object Fallback : SeedResult
        }

        private val saveMutex = Mutex()
        private val seedGenerationInProgress = AtomicBoolean(false)
        private val migratedIdentities = ConcurrentHashMap.newKeySet<String>()
        private val migratingIdentities = ConcurrentHashMap.newKeySet<String>()

        private val seedFlow: Flow<SeedResult> =
            merge(
                seedRepository.seedFlow
                    .map { s ->
                        if (s.isNotEmpty()) {
                            SeedResult.Real(s)
                        } else {
                            SeedResult.Fallback
                        }
                    }.flatMapConcat { result ->
                        if (result is SeedResult.Fallback) {
                            if (!seedGenerationInProgress.compareAndSet(false, true)) {
                                emptyFlow()
                            } else {
                                flow {
                                    try {
                                        val generated = generateSeedWithRetry()
                                        if (generated != null) {
                                            emit(SeedResult.Real(generated))
                                        } else {
                                            emit(SeedResult.Fallback)
                                        }
                                    } finally {
                                        seedGenerationInProgress.set(false)
                                    }
                                }
                            }
                        } else {
                            flowOf(result)
                        }
                    },
                flow {
                    delay(SEED_TOTAL_TIMEOUT_MS.milliseconds)
                    emit(SeedResult.Fallback)
                },
            ).scan<SeedResult, SeedResult?>(null) { acc, value ->
                if (acc is SeedResult.Real) {
                    acc
                } else {
                    value
                }
            }.filterNotNull()
                .distinctUntilChanged()
                .shareIn(scope, SharingStarted.Eagerly, replay = 1)

        private val physicalActiveSims: Flow<List<ActiveSimInfo>> =
            seedFlow
                .flatMapLatest { seed ->
                    callbackFlow {
                        val listener =
                            object : SubscriptionManager.OnSubscriptionsChangedListener() {
                                override fun onSubscriptionsChanged() {
                                    launch {
                                        try {
                                            send(getActiveSimsInternal(seed))
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            AppLogger.e("Failed to fetch active SIMs on change", e)
                                            send(emptyList())
                                        }
                                    }
                                }
                            }

                        var isListenerRegistered = false

                        fun updateListenerRegistration() {
                            val hasPermission = permissionManager.hasPhoneStatePermission()
                            if (hasPermission && !isListenerRegistered) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    subscriptionManager.addOnSubscriptionsChangedListener(
                                        context.mainExecutor,
                                        listener,
                                    )
                                } else {
                                    @Suppress("DEPRECATION")
                                    subscriptionManager.addOnSubscriptionsChangedListener(listener)
                                }
                                isListenerRegistered = true
                            } else if (!hasPermission && isListenerRegistered) {
                                subscriptionManager.removeOnSubscriptionsChangedListener(listener)
                                isListenerRegistered = false
                            }
                        }

                        launch {
                            permissionStateNotifier.changes.collect {
                                updateListenerRegistration()
                                try {
                                    if (permissionManager.hasPhoneStatePermission()) {
                                        send(getActiveSimsInternal(seed))
                                    } else {
                                        send(emptyList())
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    AppLogger.e("Failed to fetch active SIMs on permission change", e)
                                    send(emptyList())
                                }
                            }
                        }

                        // Initial fetch
                        if (permissionManager.hasPhoneStatePermission()) {
                            try {
                                val initialSims = getActiveSimsInternal(seed)
                                send(initialSims)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                AppLogger.e("Failed to fetch initial active SIMs", e)
                                trySend(emptyList())
                            }
                        }

                        awaitClose {
                            if (isListenerRegistered) {
                                subscriptionManager.removeOnSubscriptionsChangedListener(listener)
                            }
                        }
                    }
                }

        @OptIn(ExperimentalCoroutinesApi::class)
        private val activeSimsCache: StateFlow<List<ActiveSimInfo>?> =
            combine(
                physicalActiveSims,
                appSettingsRepository.observeAppSettings(),
            ) { physical, settings ->
                physical to settings
            }.flatMapLatest { (physical, settings) ->
                flow {
                    val mocks = mockSimProvider.getMockSims(settings.benchmarkSimCount)
                    if (mocks != null) {
                        emit(mocks)
                    } else {
                        emit(physical)
                    }
                }
            }.stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = null,
            )

        private suspend fun getSeed(): SeedResult {
            val seed =
                withTimeoutOrNull(SEED_TOTAL_TIMEOUT_MS.milliseconds) {
                    seedFlow.firstOrNull()
                }
            if (seed == null) {
                AppLogger.e("Identity seed unavailable, using slot-based fallback")
            }
            return seed ?: SeedResult.Fallback
        }

        override suspend fun getIdentity(slot: Int): String = getSimIdentity(slot).stableId

        override suspend fun getSimIdentity(slot: Int): SimIdentity {
            if (slot !in SimConstants.SUPPORTED_SLOT_INDICES) {
                AppLogger.w("getSimIdentity called with out-of-range slot=$slot, returning fallback")
                return createFallbackIdentity(slot, "sim_$slot")
            }

            try {
                val seed = getSeed()

                // Wait for activeSimsCache with a timeout that covers the potential seed generation time.
                // If the seed is being generated, activeSimsCache (which depends on physicalActiveSims,
                // which depends on seedFlow) will not emit until the seed is ready.
                val currentSims: List<ActiveSimInfo> =
                    activeSimsCache.filterNotNull().firstWithTimeout(SEED_TOTAL_TIMEOUT_MS)
                        ?: run {
                            AppLogger.w(
                                "SimIdentityRepository: Timeout waiting for activeSimsCache, " +
                                    "using fallback discovery",
                            )
                            emptyList()
                        }
                currentSims.find { it.slotIndex == slot }?.let {
                    return it.identity
                }

                // If not found in cache, try direct resolution
                return findActiveIdentityInternal(slot, seed) ?: createFallbackIdentity(slot, "sim_$slot")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("getSimIdentity failed for slot $slot", e)
                return createFallbackIdentity(slot, "sim_$slot")
            }
        }

        private suspend fun findActiveIdentityInternal(
            slot: Int,
            seed: SeedResult,
        ): SimIdentity? =
            try {
                permissionManager.getActiveSubscriptions().find { it.simSlotIndex == slot }?.let {
                    val resolved =
                        when (seed) {
                            is SeedResult.Fallback -> {
                                createFallbackIdentity(slot, "sim_$slot")
                            }
                            is SeedResult.Real -> {
                                SimIdentityUtils.createIdentityFromInfo(it, seed.value)
                            }
                        }
                    saveSimIdentitiesInfo(listOf(resolved))
                    resolved
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("findActiveIdentityInternal failed for slot $slot", e)
                null
            }

        private fun createFallbackIdentity(
            slot: Int,
            stableId: String,
        ) = SimIdentity(
            stableId = stableId,
            subscriptionId = null,
            slotIndex = slot,
            source = IdentitySource.SLOT_FALLBACK,
        )

        private suspend fun saveSimIdentitiesInfo(identities: List<SimIdentity>) {
            val toMigrate = mutableListOf<SimIdentity>()

            saveMutex.withLock {
                val validIdentities = identities.filter { it.source != IdentitySource.SLOT_FALLBACK }
                if (validIdentities.isEmpty()) return@withLock

                val currentPrefs =
                    dataStore.data.firstWithTimeout(Constants.Flow.LONG_TIMEOUT_MS)
                        ?: run {
                            AppLogger.w(
                                "saveSimIdentitiesInfo: DataStore read timed out, " +
                                    "skipping save to avoid redundant writes",
                            )
                            return@withLock
                        }

                val toUpdate =
                    validIdentities.filter { identity ->
                        val currentSlot = currentPrefs[SimSettingsKeys.slotIndexKey(identity.stableId)]
                        val currentSub = currentPrefs[SimSettingsKeys.subscriptionIdKey(identity.stableId)]
                        (currentSlot != identity.slotIndex) || (currentSub != identity.subscriptionId)
                    }

                if (toUpdate.isEmpty()) return@withLock

                toUpdate.forEach { identity ->
                    val currentSlot = currentPrefs[SimSettingsKeys.slotIndexKey(identity.stableId)]
                    if ((currentSlot == null) && migratedIdentities.add(identity.stableId)) {
                        toMigrate.add(identity)
                    }
                }

                dataStore.edit { prefs ->
                    toUpdate.forEach { identity ->
                        identity.slotIndex?.let { slot ->
                            prefs[SimSettingsKeys.slotIndexKey(identity.stableId)] = slot
                        }
                        identity.subscriptionId?.let { subId ->
                            prefs[SimSettingsKeys.subscriptionIdKey(identity.stableId)] = subId
                        }
                    }
                }
                performMigrations(toMigrate)
            }
        }

        private suspend fun performMigrations(toMigrate: List<SimIdentity>) {
            toMigrate.forEach { identity ->
                if (migratingIdentities.add(identity.stableId)) {
                    try {
                        migrationService.migrateLegacyData(identity)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.e("Failed to migrate legacy data for identity: ${identity.stableId}", e)
                    } finally {
                        migratingIdentities.remove(identity.stableId)
                    }
                }
            }
        }

        override fun observeActiveSims(): Flow<List<ActiveSimInfo>> =
            activeSimsCache
                .filterNotNull()

        private suspend fun getActiveSimsInternal(seed: SeedResult): List<ActiveSimInfo> {
            val infoList =
                permissionManager
                    .getActiveSubscriptions()
                    .filter { it.simSlotIndex in SimConstants.SUPPORTED_SLOT_INDICES }

            val identities =
                infoList.map { info ->
                    when (seed) {
                        is SeedResult.Fallback -> {
                            createFallbackIdentity(info.simSlotIndex, "sim_${info.simSlotIndex}")
                        }
                        is SeedResult.Real -> {
                            SimIdentityUtils.createIdentityFromInfo(info, seed.value)
                        }
                    }
                }

            val currentIdentities = identities.asSequence().map { it.stableId }.toSet()
            migratedIdentities.retainAll(currentIdentities)

            if (infoList.isEmpty()) return emptyList()

            try {
                saveSimIdentitiesInfo(identities)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Failed to save identity info for active SIMs", e)
            }

            val defaultDataSubId = permissionManager.getDefaultDataSubscriptionId()
            val defaultVoiceSubId = permissionManager.getDefaultVoiceSubscriptionId()

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

        override fun getActiveIdentities(): Flow<List<String>> =
            observeActiveSims()
                .map { sims ->
                    sims.map { it.identity.stableId }
                }.distinctUntilChanged()

        @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
        override fun observeIdentities(): Flow<List<String>> =
            getActiveIdentities()
                .debounce(Constants.Flow.DEBOUNCE_MS.milliseconds)

        override fun getSimOperator(identity: String): Flow<String> =
            combine(
                getData(SimSettingsKeys.simOperatorKey(identity), ""),
                getCustomSimOperator(identity),
            ) { resolved, custom ->
                custom ?: resolved
            }

        override fun getCustomSimOperator(identity: String): Flow<String?> =
            dataStore.data.map { it[SimSettingsKeys.customSimOperatorKey(identity)] }

        override suspend fun setSimOperator(
            identity: String,
            name: String,
        ) {
            dataStore.edit { it[SimSettingsKeys.simOperatorKey(identity)] = name }
        }

        override suspend fun setCustomSimOperator(
            identity: String,
            name: String?,
        ) {
            dataStore.edit { prefs ->
                if (name == null) {
                    prefs.remove(SimSettingsKeys.customSimOperatorKey(identity))
                } else {
                    prefs[SimSettingsKeys.customSimOperatorKey(identity)] = name
                }
            }
        }

        override fun getSimCode(identity: String): Flow<String> = getData(SimSettingsKeys.simCodeKey(identity), "")

        override suspend fun setSimCode(
            identity: String,
            code: String,
        ) {
            dataStore.edit { it[SimSettingsKeys.simCodeKey(identity)] = code }
        }

        private suspend fun generateSeedWithRetry(): String? =
            withTimeoutOrNull(Constants.Flow.SEED_GENERATION_TIMEOUT_MS.milliseconds) {
                seedRepository.getOrCreateSeed { UUID.randomUUID().toString() }
            }

        internal companion object {
            @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
            internal const val SEED_TOTAL_TIMEOUT_MS =
                (Constants.Flow.SEED_GENERATION_TIMEOUT_MS + Constants.Flow.SEED_TOTAL_TIMEOUT_EXTRA_MS)
        }
    }
