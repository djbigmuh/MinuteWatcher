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
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

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
        private val fallbackMutex = Mutex()
        private val migratedIdentities = ConcurrentHashMap.newKeySet<String>()
        private val migratingIdentities = ConcurrentHashMap.newKeySet<String>()
        private val fallbackCache = ConcurrentHashMap<Int, SimIdentity>()

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
            seedFlow
                .flatMapLatest { seed ->
                    callbackFlow {
                        var fetchJob: Job? = null

                        fun launchFetch() {
                            fetchJob?.cancel()
                            fetchJob =
                                launch {
                                    var retryDelay = Constants.Flow.RETRY_DELAY_MS
                                    val maxDelay = Constants.Flow.LONG_TIMEOUT_MS * MAX_DELAY_MULTIPLIER
                                    var consecutiveErrors = 0
                                    var shouldContinue = true
                                    while (shouldContinue) {
                                        try {
                                            send(getActiveSimsInternal(seed))
                                            shouldContinue = false
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: IOException) {
                                            consecutiveErrors++
                                            if (consecutiveErrors >= MAX_FETCH_RETRIES) {
                                                AppLogger.e(
                                                    "SimIdentityRepository: Terminal DataStore error after " +
                                                        "$consecutiveErrors attempts. " +
                                                        "Stopping retries for this change event.",
                                                    e,
                                                )
                                                send(emptyList())
                                                shouldContinue = false
                                            } else {
                                                AppLogger.e(
                                                    "SimIdentityRepository: DataStore error during fetch " +
                                                        "(attempt $consecutiveErrors), retrying in ${retryDelay}ms",
                                                    e,
                                                )
                                                delay(retryDelay.milliseconds)
                                                retryDelay = (retryDelay * 2).coerceAtMost(maxDelay)
                                            }
                                        } catch (e: Exception) {
                                            AppLogger.e("Failed to fetch active SIMs", e)
                                            send(emptyList())
                                            shouldContinue = false
                                        }
                                    }
                                }
                        }

                        val listener =
                            object : SubscriptionManager.OnSubscriptionsChangedListener() {
                                override fun onSubscriptionsChanged() {
                                    launchFetch()
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
                                if (permissionManager.hasPhoneStatePermission()) {
                                    launchFetch()
                                } else {
                                    fetchJob?.cancel()
                                    send(emptyList())
                                }
                            }
                        }

                        // Initial fetch
                        if (permissionManager.hasPhoneStatePermission()) {
                            launchFetch()
                        }

                        awaitClose {
                            fetchJob?.cancel()
                            if (isListenerRegistered) {
                                subscriptionManager.removeOnSubscriptionsChangedListener(listener)
                            }
                        }
                    }
                }

        @OptIn(ExperimentalCoroutinesApi::class)
        private val activeSimsCache: StateFlow<List<ActiveSimInfo>> =
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
                initialValue = emptyList(),
            )

        init {
            scope.launch {
                seedFlow.collect { result ->
                    if (result is SeedResult.Real) {
                        val fallbackSlots =
                            fallbackMutex.withLock {
                                fallbackCache.keys.toList()
                            }

                        if (fallbackSlots.isNotEmpty()) {
                            /**
                             * При переходе seed из Fallback в Real, миграция legacy-данных выполняется
                             * до очистки fallback-кэша. Если миграция не удаётся, кэш сохраняется
                             * для затронутого слота, обеспечивая корректный fallback при следующем запросе.
                             * На AOSP миграция не падает при наличии READ_PHONE_STATE; сбой возможен
                             * только на кастомных прошивках с повреждённым DataStore.
                             */
                            AppLogger.d(
                                "SimIdentityRepository: processing ${fallbackSlots.size} fallback cache entries " +
                                    "upon receiving real seed transition",
                            )

                            try {
                                val seedValue = result.value
                                // Сначала выполняем миграцию для всех затронутых слотов
                                var migrationSucceeded = true
                                permissionManager
                                    .getActiveSubscriptions()
                                    .forEach { sub ->
                                        val slot = sub.simSlotIndex
                                        if (fallbackSlots.contains(slot)) {
                                            try {
                                                val newIdentity =
                                                    SimIdentityUtils.createIdentityFromInfo(
                                                        info = sub,
                                                        seed = seedValue,
                                                    )
                                                migrationService.migrateLegacyData(newIdentity)
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                AppLogger.e(
                                                    "Migration failed for slot $slot after seed transition, " +
                                                        "fallback cache will be preserved for this slot",
                                                    e,
                                                )
                                                migrationSucceeded = false
                                            }
                                        }
                                    }
                                // Очищаем кэш только после успешной миграции всех слотов
                                if (migrationSucceeded) {
                                    fallbackMutex.withLock {
                                        fallbackSlots.forEach { fallbackCache.remove(it) }
                                    }
                                    AppLogger.d(
                                        "SimIdentityRepository: cleared fallback cache after successful migration",
                                    )
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                AppLogger.e("Failed to process fallback cache after seed transition", e)
                            }
                        }
                    }
                }
            }
        }

        private suspend fun getSeed(timeoutMs: Long = SEED_TOTAL_TIMEOUT_MS): SeedResult {
            val seed =
                withTimeoutOrNull(timeoutMs.milliseconds) {
                    seedFlow.firstOrNull()
                }
            if (seed == null) {
                AppLogger.e("Identity seed unavailable, using slot-based fallback")
            }
            return seed ?: SeedResult.Fallback
        }

        override suspend fun getIdentity(slot: Int): String = getSimIdentity(slot).stableId

        /**
         * Retrieves the identity for a given SIM slot.
         *
         * **Contract (AOSP):**
         * This method attempts to resolve a stable SIM identity using a cryptographically
         * secure seed. In standard AOSP with proper permissions, it uniquely identifies
         * a SIM card based on hardware attributes.
         *
         * **Heuristic/Fallback:**
         * If the slot index is outside the supported range ([SimConstants.SUPPORTED_SLOT_INDICES]),
         * or if the identity seed is unavailable after [SEED_TOTAL_TIMEOUT_MS], the method
         * falls back to a slot-based identity string ([SimConstants.INVALID_SLOT_IDENTITY_PREFIX]).
         *
         * **Boundaries:**
         * This fallback is critical for API stability. If a custom firmware prevents
         * reading hardware identifiers or misreports slot indices, the repository uses
         * these fallback identities to maintain internal state consistency. Deviations
         * from Google's telephony documentation are considered firmware-level issues.
         *
         * **Reliability Criterion:**
         * Identities starting with this prefix are used as a strict API protection
         * mechanism. They MUST NOT be saved to DataStore, and all repositories are
         * responsible for verifying this prefix before write operations. This ensures
         * that transient system states do not corrupt the permanent usage database.
         */
        override suspend fun getSimIdentity(slot: Int): SimIdentity {
            if (slot !in SimConstants.SUPPORTED_SLOT_INDICES) {
                AppLogger.w("getSimIdentity called with out-of-range slot=$slot, returning fallback")
                // G-484: Using a slot-indexed string for invalid slots to prevent
                // downstream logic from treating them as a single valid identity.
                // This stableId MUST NOT be saved to DataStore.
                return createFallbackIdentity(slot, "${SimConstants.INVALID_SLOT_IDENTITY_PREFIX}$slot")
            }

            // Мгновенное чтение текущего значения StateFlow
            val cached = activeSimsCache.value.find { it.slotIndex == slot }
            if (cached != null) {
                return cached.identity
            }

            try {
                // Используем уменьшенный таймаут для UI-операций
                val seed = getSeed(Constants.Flow.SHORT_TIMEOUT_MS)

                if (seed is SeedResult.Fallback) {
                    fallbackMutex.withLock {
                        fallbackCache[slot]?.let {
                            AppLogger.d("SimIdentityRepository: returning cached fallback for slot $slot")
                            return it
                        }
                    }
                }

                // If not found in cache, try direct resolution
                val resolved = findActiveIdentityInternal(slot, seed) ?: createFallbackIdentity(slot, "sim_$slot")

                val isFallback = resolved.source == IdentitySource.SLOT_FALLBACK
                val isInvalid = resolved.stableId.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)
                if (!isFallback && !isInvalid) {
                    if (!isIdentityAlreadyCached(slot, resolved.stableId)) {
                        saveSimIdentitiesInfo(listOf(resolved))
                    }
                }

                if (seed is SeedResult.Fallback) {
                    fallbackMutex.withLock {
                        fallbackCache[slot] = resolved
                    }
                }
                return resolved
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("getSimIdentity failed for slot $slot", e)
                return createFallbackIdentity(slot, "sim_$slot")
            }
        }

        private fun findActiveIdentityInternal(
            slot: Int,
            seed: SeedResult,
        ): SimIdentity? =
            try {
                permissionManager.getActiveSubscriptions().find { it.simSlotIndex == slot }?.let {
                    when (seed) {
                        is SeedResult.Fallback -> {
                            AppLogger.w("getSimIdentity: seed unavailable for slot $slot, using fallback identity")
                            createFallbackIdentity(slot, "sim_$slot")
                        }
                        is SeedResult.Real -> {
                            SimIdentityUtils.createIdentityFromInfo(it, seed.value)
                        }
                    }
                } ?: run {
                    AppLogger.d("getSimIdentity: no active subscription found for slot $slot, using fallback identity")
                    null
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

        /**
         * Saves SIM identities to DataStore and performs migrations if needed.
         * @throws java.io.IOException if DataStore write fails
         * @throws SecurityException if permission is revoked during operation
         */
        private suspend fun saveSimIdentitiesInfo(identities: List<SimIdentity>) {
            val toMigrate = mutableListOf<SimIdentity>()

            saveMutex.withLock {
                val validIdentities =
                    identities
                        .filter {
                            it.source != IdentitySource.SLOT_FALLBACK &&
                                !it.stableId.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)
                        }
                if (validIdentities.isEmpty()) return@withLock

                var currentPrefs = fetchCurrentPrefs()
                if (currentPrefs == null) {
                    AppLogger.w("saveSimIdentitiesInfo: DataStore read timed out, retrying with doubled timeout")
                    currentPrefs = fetchCurrentPrefs(Constants.Flow.LONG_TIMEOUT_MS * 2)
                }

                if (currentPrefs == null) {
                    AppLogger.e(
                        "saveSimIdentitiesInfo: DataStore read failed after retry, " +
                            "could not save identities: $validIdentities",
                    )
                    throw IOException("DataStore read timed out after retry")
                }

                val toUpdate =
                    validIdentities.filter { identity ->
                        isIdentityUpdateRequired(identity, currentPrefs)
                    }

                if (toUpdate.isEmpty()) return@withLock

                toUpdate.forEach { identity ->
                    if (isMigrationRequired(identity, currentPrefs)) {
                        toMigrate.add(identity)
                    }
                }

                // Обновление настроек выполняем атомарно под замком.
                updatePrefsWithIdentities(toUpdate)
            }
            // Запуск миграции выносим за пределы мьютекса, так как она может быть медленной.
            performMigrationsIfNeeded(toMigrate)
        }

        private suspend fun fetchCurrentPrefs(timeoutMs: Long = Constants.Flow.LONG_TIMEOUT_MS): Preferences? =
            dataStore.data.firstWithTimeout(timeoutMs)
                ?: run {
                    AppLogger.w(
                        "saveSimIdentitiesInfo: DataStore read timed out (timeout=${timeoutMs}ms), " +
                            "identity update skipped",
                    )
                    null
                }

        private fun isIdentityAlreadyCached(
            slot: Int,
            stableId: String,
        ): Boolean = activeSimsCache.value.any { it.slotIndex == slot && it.identity.stableId == stableId }

        private fun isIdentityUpdateRequired(
            identity: SimIdentity,
            prefs: Preferences,
        ): Boolean {
            val currentSlot = prefs[SimSettingsKeys.slotIndexKey(identity.stableId)]
            val currentSub = prefs[SimSettingsKeys.subscriptionIdKey(identity.stableId)]
            return (currentSlot != identity.slotIndex) || (currentSub != identity.subscriptionId)
        }

        private fun isMigrationRequired(
            identity: SimIdentity,
            prefs: Preferences,
        ): Boolean {
            val currentSlot = prefs[SimSettingsKeys.slotIndexKey(identity.stableId)]
            return (currentSlot == null) && migratedIdentities.add(identity.stableId)
        }

        private suspend fun updatePrefsWithIdentities(toUpdate: List<SimIdentity>) {
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

        private suspend fun performMigrationsIfNeeded(toMigrate: List<SimIdentity>) {
            if (toMigrate.isNotEmpty()) {
                try {
                    performMigrations(toMigrate)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Migration failed in saveSimIdentitiesInfo", e)
                }
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

        override fun observeActiveSims(): Flow<List<ActiveSimInfo>> = activeSimsCache

        /**
         * Internal method to fetch active SIM information and persist their identities.
         *
         * Strategy for DataStore errors (IOException):
         * 1. **Exponential Backoff**: If [saveSimIdentitiesInfo] fails due to DataStore timeout,
         *    the caller retries with an increasing delay.
         * 2. **Retry Budget**: Retries are limited to [MAX_FETCH_RETRIES].
         * 3. **Terminal State**: If the budget is exhausted, an empty list is emitted to avoid
         *    infinite background loops.
         * 4. **Recovery**: The process will be re-triggered automatically upon the next SIM
         *    subscription change (via SubscriptionManager) or if permissions are toggled.
         *
         * @throws java.io.IOException if DataStore write fails after internal retries within [saveSimIdentitiesInfo].
         */
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
            } catch (e: IOException) {
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

        @OptIn(FlowPreview::class)
        override fun observeIdentities(): Flow<List<String>> =
            getActiveIdentities()
                .debounce(Constants.Flow.DEBOUNCE_MS.milliseconds)

        override fun getSimOperator(identity: String): Flow<String> {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("getSimOperator called with invalid identity: $identity")
                return kotlinx.coroutines.flow.flowOf("")
            }
            return combine(
                getData(SimSettingsKeys.simOperatorKey(identity), ""),
                getCustomSimOperator(identity),
            ) { resolved, custom ->
                custom ?: resolved
            }
        }

        override fun getCustomSimOperator(identity: String): Flow<String?> {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("getCustomSimOperator called with invalid identity: $identity")
                return kotlinx.coroutines.flow.flowOf(null)
            }
            return dataStore.data.map { it[SimSettingsKeys.customSimOperatorKey(identity)] }
        }

        override suspend fun setSimOperator(
            identity: String,
            name: String,
        ) {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("setSimOperator called with invalid identity: $identity")
                return
            }
            dataStore.edit { it[SimSettingsKeys.simOperatorKey(identity)] = name }
        }

        override suspend fun setCustomSimOperator(
            identity: String,
            name: String?,
        ) {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("setCustomSimOperator called with invalid identity: $identity")
                return
            }
            dataStore.edit { prefs ->
                if (name == null) {
                    prefs.remove(SimSettingsKeys.customSimOperatorKey(identity))
                } else {
                    prefs[SimSettingsKeys.customSimOperatorKey(identity)] = name
                }
            }
        }

        override fun getSimCode(identity: String): Flow<String> {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("getSimCode called with invalid identity: $identity")
                return kotlinx.coroutines.flow.flowOf("")
            }
            return getData(SimSettingsKeys.simCodeKey(identity), "")
        }

        override suspend fun setSimCode(
            identity: String,
            code: String,
        ) {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("setSimCode called with invalid identity: $identity")
                return
            }
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
            private const val MAX_DELAY_MULTIPLIER = 3
            private const val MAX_FETCH_RETRIES = 5
        }
    }
