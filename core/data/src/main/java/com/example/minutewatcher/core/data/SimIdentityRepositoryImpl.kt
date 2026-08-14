package com.example.minutewatcher.core.data

import android.os.Build
import android.telephony.SubscriptionInfo
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

// Подавление TooManyFunctions оставлено намеренно: SimIdentityRepositoryImpl является центральным узлом управления
// идентичностью всех SIM-карт в приложении. Разделение на несколько репозиториев нарушит атомарность
// управления кэшем и DataStore, что критично для стабильности identity.
@Suppress("TooManyFunctions")
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
        private val migratedIdentities = ConcurrentHashMap.newKeySet<String>()
        private val migratingIdentities = ConcurrentHashMap.newKeySet<String>()

        /**
         * Кэш результатов прямого резолва SIM-идентичности.
         * Заполняется при вызове getSimIdentity, когда activeSimsCache ещё не содержит данных.
         * Очищается при получении актуального списка активных SIM из activeSimsCache.
         *
         * Хрупкая эвристика: если activeSimsCache не эмитит значение в течение длительного
         * времени (деградация DataStore на кастомной прошивке), записи могут устареть.
         * Строгое соблюдение API: на AOSP observeActiveSims() эмитит значение
         * в пределах SHORT_TIMEOUT_MS. Проблема возможна только на кастомных прошивках,
         * не соответствующих документации Google.
         */
        private val manualResolutionCache = ConcurrentHashMap<Int, SimIdentity>()

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
                started = SharingStarted.WhileSubscribed(Constants.Flow.WHILE_SUBSCRIBED_STOP_TIMEOUT_MS),
                initialValue = emptyList(),
            )
        // WhileSubscribed останавливает наблюдение через 5 секунд после ухода последнего
        // подписчика. Это предотвращает фоновое потребление ресурсов, когда приложение
        // не активно. Строгое соблюдение API: на AOSP SubscriptionManager callback
        // доставляется мгновенно. При возобновлении подписки первое значение эмитится
        // в пределах миллисекунд. Стратегия: экономия батареи в фоне без потери
        // функциональности при возвращении в foreground.

        init {
            scope.launch {
                activeSimsCache.collect { sims ->
                    // Очищаем manualResolutionCache для слотов, которых нет в активном списке.
                    // Строгое соблюдение API: на AOSP SubscriptionManager callback доставляется
                    // при каждом изменении подписок. Если кастомная прошивка не доставляет
                    // callback — это проблема кастомной прошивки.
                    val activeSlots = sims.asSequence().map { it.slotIndex }.toSet()
                    manualResolutionCache.keys.removeAll { it !in activeSlots }
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
         * Возвращает стабильную идентичность SIM для указанного слота.
         *
         * @param slot Индекс слота SIM. Должен быть в диапазоне
         *   [SimConstants.SUPPORTED_SLOT_INDICES].
         * @throws IllegalArgumentException если slot вне допустимого диапазона.
         * @throws IllegalStateException если SIM не найдена в указанном слоте
         *   или identity seed недоступен (деградация DataStore на кастомной
         *   прошивке, не соответствующей документации Google).
         */
        @Suppress("ThrowsCount", "ReturnCount")
        // Подавление ThrowsCount и ReturnCount оставлено намеренно: метод реализует строгую валидацию по трем
        // независимым критериям (диапазон слотов, наличие seed, наличие подписки). Объединение исключений
        // и точек выхода снизит точность диагностики сбоев на кастомных прошивках.
        override suspend fun getSimIdentity(slot: Int): SimIdentity {
            require(slot in SimConstants.SUPPORTED_SLOT_INDICES) {
                "getSimIdentity called with out-of-range slot=$slot. " +
                    "Supported range: ${SimConstants.SUPPORTED_SLOT_INDICES}"
            }

            // Мгновенное чтение текущего значения StateFlow
            val cached = activeSimsCache.value.find { it.slotIndex == slot }
            if (cached != null) {
                manualResolutionCache.remove(slot)
                return cached.identity
            }

            val manuallyResolved = manualResolutionCache[slot]
            if (manuallyResolved != null) return manuallyResolved

            try {
                // Используем уменьшенный таймаут для UI-операций
                val seed = getSeed(Constants.Flow.SHORT_TIMEOUT_MS)

                if (seed is SeedResult.Fallback) {
                    // Seed недоступен — это НЕ "SIM удалена", а деградация хранилища.
                    // Строгое соблюдение API: на AOSP генерация seed завершается успешно.
                    // Таймаут возможен только при деградации DataStore на кастомных
                    // прошивках, не соответствующих документации Google.
                    val message =
                        "Identity seed unavailable for slot $slot. " +
                            "Possible DataStore degradation on non-AOSP firmware."
                    AppLogger.e(message, null)
                    throw IllegalStateException(message)
                }

                // If not found in cache, try direct resolution
                val resolved =
                    findActiveIdentityInternal(slot, seed)
                        ?: error("No active SIM subscription found in slot $slot")

                manualResolutionCache[slot] = resolved

                if (!isIdentityAlreadyCached(slot, resolved.stableId)) {
                    saveSimIdentitiesInfo(listOf(resolved))
                    // Кэш activeSimsCache обновляется асинхронно через observation flow.
                    // Прямой резолвинг — fallback для случаев, когда observation flow ещё не
                    // обновил кэш (например, сразу после запуска приложения).
                    // Строгое соблюдение API: на AOSP observeActiveSims() эмитит первое значение
                    // в пределах миллисекунд. Прямой резолвинг нужен только для кастомных прошивок
                    // с задержкой callback от SubscriptionManager. Это проблема кастомной прошивки.
                    // Стратегия: прямой резолвинг — защитный механизм, не штатный путь.
                }

                return resolved
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("getSimIdentity failed for slot $slot", e)
                throw e
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
                    // Строгое соблюдение API: на AOSP getActiveSubscriptions() возвращает
                    // актуальный список при наличии READ_PHONE_STATE. Возврат null означает,
                    // что SIM физически отсутствует в слоте. Это штатная ситуация, а не ошибка.
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
                            (it.source != IdentitySource.SLOT_FALLBACK) &&
                                (!it.stableId.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX))
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

        @Suppress("ReturnCount")
        // Подавление ReturnCount оставлено намеренно: метод содержит 3 точки выхода, соответствующие
        // различным терминальным состояниям (отсутствие seed, отсутствие SIM, успешное завершение).
        // Усложнение структуры ради одного возврата снизит читаемость критической секции обнаружения SIM.
        private suspend fun getActiveSimsInternal(seed: SeedResult): List<ActiveSimInfo> {
            if (seed is SeedResult.Fallback) {
                // Строгое соблюдение API: на AOSP генерация seed завершается успешно.
                // Таймаут генерации возможен только при деградации DataStore
                // на кастомных прошивках, не соответствующих документации Google.
                // Если кастомная прошивка не соответствует документации Google —
                // это проблема кастомной прошивки.
                AppLogger.e("Cannot fetch active SIMs: identity seed unavailable", null)
                return emptyList()
            }

            val seedValue = (seed as SeedResult.Real).value
            val infoList =
                permissionManager
                    .getActiveSubscriptions()
                    .filter { it.simSlotIndex in SimConstants.SUPPORTED_SLOT_INDICES }

            val identities = mapInfoListToIdentities(infoList, seedValue)

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

            return createActiveSimInfoList(infoList, identities)
        }

        /**
         * Маппинг информации о подписках в идентичности SIM.
         */
        private fun mapInfoListToIdentities(
            infoList: List<SubscriptionInfo>,
            seedValue: String,
        ): List<SimIdentity> =
            infoList.map { info ->
                SimIdentityUtils.createIdentityFromInfo(info, seedValue)
            }

        /**
         * Создание списка ActiveSimInfo на основе подписок и идентичностей.
         */
        private fun createActiveSimInfoList(
            infoList: List<SubscriptionInfo>,
            identities: List<SimIdentity>,
        ): List<ActiveSimInfo> {
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
