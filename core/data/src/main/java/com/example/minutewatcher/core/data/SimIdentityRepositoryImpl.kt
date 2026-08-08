package com.example.minutewatcher.core.data

import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.data.di.SimDataStore
import com.example.minutewatcher.core.data.util.SimIdentityUtils
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.Constants.SimConstants
import com.example.minutewatcher.core.model.IdentitySource
import com.example.minutewatcher.core.model.SimIdentity
import dagger.hilt.android.qualifiers.ApplicationContext
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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
@Suppress("LongParameterList")
class SimIdentityRepositoryImpl
    @Inject
    constructor(
        /**
         * Context used for obtaining [Context.getMainExecutor] when registering
         * subscription change listeners on Android R+.
         */
        @param:ApplicationContext private val context: Context,
        private val subscriptionManager: SubscriptionManager,
        private val permissionManager: PermissionManager,
        permissionStateNotifier: PermissionStateNotifier,
        @SimDataStore dataStore: DataStore<Preferences>,
        private val seedRepository: SimIdentitySeedRepository,
        appSettingsRepository: AppSettingsRepository,
        private val migrationService: IdentityMigrationService,
        private val mockSimProvider: MockSimProvider,
        @ApplicationScope private val scope: CoroutineScope,
    ) : BaseDataStoreRepository(dataStore),
        SimIdentityRepository {
        private sealed interface SeedResult {
            data class Real(
                val value: String,
            ) : SeedResult

            object Fallback : SeedResult
        }

        private val saveMutex = Mutex()
        private val migratedIdentities =
            ConcurrentHashMap
                .newKeySet<String>()

        private val seedFlow: Flow<SeedResult> =
            flow {
                var currentResult: SeedResult? = null
                seedRepository.seedFlow.collect { s ->
                    // Если стабильный сид уже получен, игнорируем любые последующие эмиссии из DataStore
                    if (currentResult is SeedResult.Real) {
                        return@collect
                    }

                    val nextResult =
                        if (s.isNotEmpty()) {
                            SeedResult.Real(s)
                        } else {
                            val generated = generateSeedWithRetry()
                            if (generated != null) {
                                SeedResult.Real(generated)
                            } else {
                                SeedResult.Fallback
                            }
                        }

                    // Эмитим только если состояние изменилось (аналог distinctUntilChanged)
                    if (currentResult != nextResult) {
                        currentResult = nextResult
                        emit(nextResult)
                    }
                }
            }.catch { e ->
                AppLogger.e("seedFlow upstream error, emitting Fallback", e)
                emit(SeedResult.Fallback)
            }.shareIn(scope, SharingStarted.Eagerly, replay = 1)

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

            val seed = getSeed()

            val currentSims: List<ActiveSimInfo> =
                activeSimsCache.filterNotNull().firstWithTimeout(Constants.Flow.LONG_TIMEOUT_MS) ?: run {
                    AppLogger
                        .w("SimIdentityRepository: Timeout waiting for activeSimsCache, using fallback discovery")
                    emptyList()
                }
            currentSims.find { it.slotIndex == slot }?.let {
                return it.identity
            }

            // If not found in cache, try direct resolution
            return findActiveIdentityInternal(slot, seed) ?: createFallbackIdentity(slot, "sim_$slot")
        }

        private suspend fun findActiveIdentityInternal(
            slot: Int,
            seed: SeedResult,
        ): SimIdentity? =
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

                val currentPrefs = dataStore.data.firstWithTimeout() ?: emptyPreferences()

                val toUpdate =
                    validIdentities.filter { identity ->
                        val currentSlot = currentPrefs[SimSettingsKeys.slotIndexKey(identity.stableId)]
                        val currentSub = currentPrefs[SimSettingsKeys.subscriptionIdKey(identity.stableId)]
                        (currentSlot != identity.slotIndex) || (currentSub != identity.subscriptionId)
                    }

                if (toUpdate.isEmpty()) return@withLock

                toUpdate.forEach { identity ->
                    val currentSlot = currentPrefs[SimSettingsKeys.slotIndexKey(identity.stableId)]
                    if (currentSlot == null && migratedIdentities.add(identity.stableId)) {
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
            }

            // Миграция вне мьютекса
            toMigrate.forEach { identity ->
                try {
                    migrationService.migrateLegacyData(identity)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e(
                        "Failed to migrate legacy data for identity: ${identity.stableId}",
                        e,
                    )
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

            val currentIdentities = identities.map { it.stableId }.toSet()
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

        private suspend fun generateSeedWithRetry(): String? {
            for (attempt in 1..SEED_RETRY_ATTEMPTS) {
                val result =
                    withTimeoutOrNull(Constants.Flow.SEED_GENERATION_TIMEOUT_MS.milliseconds) {
                        seedRepository.getOrCreateSeed { UUID.randomUUID().toString() }
                    }
                if (result != null) return result
                if (attempt < SEED_RETRY_ATTEMPTS) {
                    AppLogger.w("Identity seed generation timeout, retrying ($attempt/$SEED_RETRY_ATTEMPTS)...")
                    delay(Constants.Flow.RETRY_DELAY_MS.milliseconds)
                }
            }
            return null
        }

        companion object {
            private const val SEED_RETRY_ATTEMPTS = 2
            private val SEED_TOTAL_TIMEOUT_MS =
                (Constants.Flow.SEED_GENERATION_TIMEOUT_MS * SEED_RETRY_ATTEMPTS + 2_000L)
        }
    }
