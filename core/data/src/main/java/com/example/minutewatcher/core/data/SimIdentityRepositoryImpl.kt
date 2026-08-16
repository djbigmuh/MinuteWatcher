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
import com.example.minutewatcher.core.domain.SeedUnavailableException
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.domain.SimNotFoundException
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
         * Кэш результатов прямого резолва SIM-identity.
         *
         * Используется только в случаях, когда [activeSimsCache] не содержит данных для
         * запрашиваемого слота (например, сразу после запуска приложения или если разрешения
         * были временно отозваны).
         *
         * **Строгое соблюдение API:** на AOSP [observeActiveSims] эмитит значение в течение
         * миллисекунд после изменения разрешений или состояния SIM. Кэш служит защитным
         * механизмом для предотвращения избыточных вызовов [findActiveIdentityInternal]
         * в короткие промежутки времени.
         *
         * **Отклонение кастомной прошивки:** если [activeSimsCache] не обновляется немедленно
         * при изменении разрешений — это проблема кастомной прошивки.
         *
         * **Безопасность (TTL):** записи в кэше имеют ограниченный срок жизни
         * ([MANUAL_RESOLUTION_CACHE_TTL_MS]), что предотвращает использование устаревших
         * identity в случае, если состав SIM-карт изменился, пока приложение не имело
         * доступа к [SubscriptionManager].
         *
         * **Потокобезопасность:** [ConcurrentHashMap] обеспечивает безопасный доступ из
         * разных потоков.
         */
        private val manualResolutionCache = ConcurrentHashMap<Int, Pair<SimIdentity, Long>>()

        /**
         * Поток seed-значения для генерации identity.
         *
         * **Штатный путь:** seed читается/генерируется один раз и сохраняется в DataStore.
         * Эмитит [SeedResult.Real] с валидным значением.
         *
         * **Отклонение кастомной прошивки:** при недоступности DataStore
         * (таймаут SEED_TOTAL_TIMEOUT_MS) эмитит [SeedResult.Fallback].
         * Это проблема кастомной прошивки, не соответствующей документации Google.
         *
         * **Поведение при Fallback:** все вызовы getSimIdentity() бросят
         * [SeedUnavailableException]. UI должен показать ошибку и предложить retry.
         * SIM-карты не будут отображаться до восстановления DataStore.
         * Это безопаснее, чем использование нестабильного slot-based fallback,
         * который приведёт к потере привязки данных к SIM.
         */
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

                        // Запуск получения данных SIM.
                        // Вызывается при регистрации listener и при изменении разрешения.
                        // Повторные вызовы безопасны: предыдущий job отменяется перед запуском нового.
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
                                try {
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
                                } catch (e: SecurityException) {
                                    // Разрешение было отозвано между проверкой и регистрацией.
                                    // **Контракт AOSP:** На AOSP SecurityException при регистрации
                                    // listener невозможен при наличии READ_PHONE_STATE.
                                    // **Отклонение кастомной прошивки:** Если прошивка бросает
                                    // SecurityException при наличии разрешения — это проблема прошивки.
                                    AppLogger.w("Failed to register SubscriptionsChangedListener", e)
                                    isListenerRegistered = false
                                }
                            } else if (!hasPermission && isListenerRegistered) {
                                try {
                                    subscriptionManager.removeOnSubscriptionsChangedListener(listener)
                                } catch (e: Exception) {
                                    AppLogger.w("Failed to unregister SubscriptionsChangedListener", e)
                                }
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
        // Eagerly гарантирует, что upstream активен с момента создания репозитория
        // и не зависит от наличия внешних коллекторов.
        // Ранее использовался WhileSubscribed(5000), что создавало хрупкую
        // зависимость от внутреннего коллектора в init-блоке: его отмена
        // или удаление заморозило бы кэш на последнем значении.
        //
        // Строгое соблюдение API: на AOSP SubscriptionManager.OnSubscriptionsChangedListener
        // доставляет callback при каждом изменении подписок. Eagerly гарантирует,
        // что ни один callback не будет пропущен из-за остановки upstream.

        init {
            scope.launch {
                activeSimsCache.collect { sims ->
                    // **Контракт AOSP:** на AOSP SubscriptionManager callback доставляется при
                    // каждом изменении подписок.
                    //
                    // **Отклонение кастомной прошивки:** Если кастомная прошивка не доставляет callback —
                    // это проблема кастомной прошивки.
                    //
                    // **Стратегия приложения:** Очищаем manualResolutionCache для слотов,
                    // которых нет в активном списке.
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

            val cachedManual = manualResolutionCache[slot]
            if (cachedManual != null) {
                val (identity, timestamp) = cachedManual
                if (System.currentTimeMillis() - timestamp < MANUAL_RESOLUTION_CACHE_TTL_MS) {
                    return identity
                } else {
                    manualResolutionCache.remove(slot)
                }
            }

            try {
                // Используем уменьшенный таймаут для UI-операций
                val seed = getSeed(Constants.Flow.SHORT_TIMEOUT_MS)

                if (seed is SeedResult.Fallback) {
                    AppLogger.e("Identity seed unavailable for slot $slot. Possible DataStore degradation.", null)
                    // SeedUnavailableException выбрасывается при недоступности seed.
                    // Строгое соблюдение API: на AOSP DataStore отвечает < 100 мс.
                    // Таймаут возможен только при деградации хранилища на кастомной прошивке.
                    // Стратегия: выбрасываем исключение, чтобы вызывающий код мог
                    // информировать пользователя. Fallback на slot-based identity
                    // намеренно исключён: он приведёт к потере привязки данных к SIM
                    // при восстановлении хранилища.
                    throw SeedUnavailableException(slot)
                }

                // If not found in cache, try direct resolution
                val resolved =
                    findActiveIdentityInternal(slot, seed as SeedResult.Real)
                        ?: throw SimNotFoundException(slot)

                manualResolutionCache[slot] = resolved to System.currentTimeMillis()

                if (!isIdentityAlreadyCached(slot, resolved.stableId)) {
                    saveSimIdentitiesInfo(listOf(resolved))
                    // **Контракт AOSP:** на AOSP observeActiveSims() эмитит первое значение в пределах миллисекунд.
                    //
                    // **Отклонение кастомной прошивки:** Прямой резолвинг нужен только для кастомных
                    // прошивок с задержкой callback от SubscriptionManager. Это проблема кастомной прошивки.
                    //
                    // **Стратегия приложения:** прямой резолвинг используется как защитный механизм.
                }

                return resolved
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("getSimIdentity failed for slot $slot", e)
                throw e
            }
        }

        /**
         * Разрешает identity для слота по подписке.
         *
         * @param seed Только [SeedResult.Real]. Вызывающий код обязан проверить
         *   отсутствие [SeedResult.Fallback] до вызова этого метода.
         */
        private fun findActiveIdentityInternal(
            slot: Int,
            seed: SeedResult.Real,
        ): SimIdentity? {
            val seedValue = seed.value
            return try {
                permissionManager.getActiveSubscriptions().find { it.simSlotIndex == slot }?.let {
                    SimIdentityUtils.createIdentityFromInfo(it, seedValue)
                } ?: run {
                    AppLogger.d("getSimIdentity: no active subscription found for slot $slot, using fallback identity")
                    // **Контракт AOSP:** на AOSP getActiveSubscriptions() возвращает актуальный
                    // список при наличии READ_PHONE_STATE.
                    //
                    // **Отклонение кастомной прошивки:** Возврат null означает, что SIM физически отсутствует в слоте.
                    //
                    // **Стратегия приложения:** Это штатная ситуация, возвращается null для обработки вызывающим кодом.
                    null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("findActiveIdentityInternal failed for slot $slot", e)
                null
            }
        }

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
                // **Контракт AOSP:** на AOSP генерация seed завершается успешно.
                //
                // **Отклонение кастомной прошивки:** Таймаут генерации возможен только при деградации DataStore
                // на кастомных прошивках. Это проблема кастомной прошивки.
                //
                // **Стратегия приложения:** логируется ошибка, возвращается пустой список.
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
            /**
             * Срок жизни записи в [manualResolutionCache] в миллисекундах.
             * 60 секунд — достаточный интервал для предотвращения гонок при изменении разрешений.
             */
            private const val MANUAL_RESOLUTION_CACHE_TTL_MS = 60_000L

            /**
             * Общий таймаут инициализации seed, включая генерацию и сохранение.
             * Сумма [Constants.Flow.SEED_GENERATION_TIMEOUT_MS] и [Constants.Flow.SEED_TOTAL_TIMEOUT_EXTRA_MS].
             */
            @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
            internal const val SEED_TOTAL_TIMEOUT_MS =
                (Constants.Flow.SEED_GENERATION_TIMEOUT_MS + Constants.Flow.SEED_TOTAL_TIMEOUT_EXTRA_MS)
            private const val MAX_DELAY_MULTIPLIER = 3
            private const val MAX_FETCH_RETRIES = 5
        }
    }
