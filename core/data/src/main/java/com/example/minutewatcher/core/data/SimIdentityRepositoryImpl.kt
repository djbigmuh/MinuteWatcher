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
import com.example.minutewatcher.core.common.util.TimeProvider
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
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
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
        private val timeProvider: TimeProvider,
        @ApplicationScope private val scope: CoroutineScope,
    ) : BaseDataStoreRepository(dataStore),
        SimIdentityRepository {
        // Preferences.Key is a DataStore implementation detail.
        // It MUST NOT leak into the domain layer (SettingsRepository interface).
        // Domain operates on business models only; mapping is an Impl responsibility.

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
         * Кэш результатов прямого резолва SIM identity.
         * Используется ТОЛЬКО когда SIM не найдена в [activeSimsCache].
         *
         * Потокобезопасность: ConcurrentHashMap обеспечивает потокобезопасность.
         * Доступ из getSimIdentity() (чтение/запись) и из init-блока (удаление).
         * init-блок выполняется один раз при конструировании, getSimIdentity()
         * вызывается позже. После конструирования оба доступа конкурентны,
         * ConcurrentHashMap обрабатывает это корректно.
         *
         * Инвалидация:
         * - При удалении слота из активного списка (в init-блоке при эмиссии activeSimsCache).
         * - При истечении TTL ([MANUAL_RESOLUTION_CACHE_TTL_MS]).
         * - При нахождении SIM в [activeSimsCache] (запись удаляется).
         *
         * Строгое соблюдение API: на AOSP observeActiveSims() эмитит значения
         * последовательно. WhileSubscribed гарантирует остановку потока при
         * отсутствии подписчиков.
         */
        private val manualResolutionCache = ConcurrentHashMap<Int, Pair<SimIdentity, Long>>()

        /**
         * Поток seed-значения для генерации identity SIM-карт.
         *
         * Поведение при первом запуске:
         * - onStart блокирует подписчика на время до [SEED_TOTAL_TIMEOUT_MS] мс.
         * - Если seed доступен — эмитит Real(seed).
         * - Если seed недоступен (таймаут) — эмитит Fallback.
         * - Когда seed становится доступен (после генерации), upstream эмитит Real(seed),
         *   и distinctUntilChanged() пропускает переход Fallback → Real.
         *
         * Строгое соблюдение API: на AOSP генерация и сохранение seed занимают
         * миллисекунды. Fallback возможен только при деградации DataStore
         * на кастомной прошивке. Это проблема кастомной прошивки.
         *
         * При Fallback вызов getSimIdentity() бросает SeedUnavailableException.
         * Вызывающий код должен обрабатывать это как retryable-ошибку.
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
                    // Строгое соблюдение API: на AOSP seedFlow эмитит Real seed
                    // при первом успешном чтении DataStore. Переход Fallback → Real
                    // вызывает flatMapLatest, который отменяет старый callbackFlow
                    // и запускает новый. Окно между отменой и новым fetch составляет
                    // миллисекунды и не влияет на корректность: activeSimsCache
                    // обновится в новом callbackFlow.
                    // На кастомных прошивках с деградацией DataStore seed может
                    // остаться Fallback — это проблема кастомной прошивки.
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
                                        } catch (e: CancellationException) {
                                            throw e
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
                                    // API 30+ introduced registerSubscriptionCallback (thread-safe, executor-based).
                                    // Pre-30: fall back to addOnSubscriptionsChangedListener.
                                    // See: https://developer.android.com/reference/android/telephony/SubscriptionManager#registerSubscriptionCallback
                                    // We do not guess behavior across versions — strict branching per documented API.
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
            // Корутина очистки manualResolutionCache при изменении активных SIM.
            // Запускается один раз при конструировании репозитория.
            // При каждой эмиссии activeSimsCache удаляет записи для слотов,
            // которые больше не активны.
            //
            // Потокобезопасность: ConcurrentHashMap.keys.removeAll потокобезопасен.
            // Корутина привязана к scope (ApplicationScope), живёт до завершения процесса.
            activeSimsCache
                .onEach { sims ->
                    // Инвалидация manualResolutionCache при обновлении activeSimsCache.
                    // Удаляются записи для слотов, отсутствующих в новом активном списке.
                    // Записи для активных слотов не удаляются, но и не используются
                    // (SIM найдётся в activeSimsCache).
                    //
                    // Строгое соблюдение API: на AOSP SubscriptionManager callback
                    // доставляет при каждом изменении подписок.
                    //
                    // Отклонение кастомной прошивки: если callback не доставляется
                    // при изменении разрешений — это проблема прошивки.
                    val activeSlots = sims.asSequence().map { it.slotIndex }.toSet()
                    manualResolutionCache.keys.removeAll { it !in activeSlots }
                }
                // This Flow is collected internally (fire-and-forget side effect).
                // We use .launchIn(scope) explicitly to document intent.
                // Do NOT remove the collector — Flow is cold and does nothing without it.
                .launchIn(scope)
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

        /**
         * Возвращает строковую идентичность SIM для указанного слота.
         *
         * Вызывающий код ОБЯЗАН обрабатывать оба исключения:
         * - [SeedUnavailableException] — retryable (повторить при следующем вызове).
         * - [SimNotFoundException] — не retryable (SIM извлечена).
         *
         * @param slot Индекс слота SIM.
         * @return Уникальный стабильный идентификатор SIM.
         * @throws IllegalArgumentException если slot вне диапазона [SimConstants.SUPPORTED_SLOT_INDICES].
         * @throws SeedUnavailableException если identity seed недоступен (таймаут или Fallback).
         *   Строгое соблюдение API: на AOSP seed генерируется и сохраняется за миллисекунды.
         *   `SeedUnavailableException` возможна только при деградации DataStore на кастомной прошивке.
         *   Это проблема кастомной прошивки, не проекта.
         * @throws SimNotFoundException если активная подписка SIM физически отсутствует в указанном слоте.
         */
        override suspend fun getIdentity(slot: Int): String = getSimIdentity(slot).stableId

        /**
         * Возвращает стабильную идентичность SIM для указанного слота.
         *
         * **Строгое соблюдение API:** на AOSP `getIdentity()` не бросает исключений при
         * корректном состоянии DataStore и наличии SIM. `SeedUnavailableException` возможен
         * только при деградации хранилища на кастомной прошивке (проблема прошивки).
         *
         * При Fallback seed (таймаут или реальная недоступность) метод бросает
         * SeedUnavailableException. Вызывающий код ОБЯЗАН обрабатывать это как
         * retryable-ошибку: повторить при следующем вызове.
         *
         * Строгое соблюдение API: на AOSP seed генерируется и сохраняется за
         * миллисекунды. Fallback возможен только при деградации DataStore
         * на кастомной прошивке. Это проблема кастомной прошивки.
         *
         * Стратегия вызывающего кода:
         * - SeedUnavailableException → retryable, повторить позже.
         * - SimNotFoundException → не retryable, SIM извлечена.
         * - IllegalArgumentException → программирующая ошибка, не retryable.
         *
         * **Эвристика в пределах критерия надёжности:** при `subId == -1` (кастомная прошивка
         * не передала subscription ID) применяется fallback на `getDefaultVoiceSubscriptionId()`.
         *
         * Точки возврата:
         * 1. activeSimsCache — SIM найдена в кэше активных.
         * 2. manualResolutionCache — SIM найдена в ручном кэше (TTL не истёк).
         * 3. findActiveIdentityInternal — SIM найдена прямым запросом.
         *
         * Вызывающий код ОБЯЗАН обрабатывать оба исключения:
         * - [SeedUnavailableException] — retryable (повторить при следующем вызове).
         * - [SimNotFoundException] — не retryable (SIM извлечена).
         *
         * @param slot Индекс слота SIM. Должен быть в [SimConstants.SUPPORTED_SLOT_INDICES].
         * @throws IllegalArgumentException если slot вне диапазона
         *   [SimConstants.SUPPORTED_SLOT_INDICES]. Это программирующая ошибка,
         *   вызывающий код не должен передавать невалидный slot.
         * @throws SeedUnavailableException если identity seed недоступен (таймаут или Fallback).
         *   Строгое соблюдение API: на AOSP seed генерируется и сохраняется за миллисекунды.
         *   `SeedUnavailableException` возможна только при деградации DataStore на кастомной прошивке.
         *   Это проблема кастомной прошивки, не проекта.
         * @throws SimNotFoundException если активная подписка SIM физически отсутствует в указанном слоте.
         * @throws CancellationException пробрасывается для корректной отмены корутины.
         */
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
                // Строгое соблюдение API: для измерения TTL используется монотонное время
                // (elapsedRealtime), а не wall-clock (currentTimeMillis). Это исключает
                // влияние перевода системных часов на время жизни кэша.
                // На кастомных прошивках, где elapsedRealtime не монотонен, — проблема прошивки.
                if (timeProvider.elapsedRealtime() - timestamp < MANUAL_RESOLUTION_CACHE_TTL_MS) {
                    return identity
                } else {
                    manualResolutionCache.remove(slot)
                }
            }

            // При первом вызове после старта приложения activeSimsCache может быть
            // пустым (seed ещё не эмитился). В этом случае выполняется прямой резолв.
            // Если seed недоступен (Fallback), бросается SeedUnavailableException.
            // Вызывающий код должен обработать это исключение.
            // На AOSP seed становится доступен в течение миллисекунд после старта.
            // Задержка возможна только при деградации DataStore на кастомной прошивке.
            try {
                // Используем уменьшенный таймаут для UI-операций
                val seed = getSeed(Constants.Flow.SHORT_TIMEOUT_MS)

                val realSeed =
                    when (seed) {
                        is SeedResult.Real -> seed
                        is SeedResult.Fallback -> {
                            AppLogger.e(
                                "Identity seed unavailable for slot $slot. Possible DataStore degradation.",
                                null,
                            )
                            // SeedUnavailableException выбрасывается при недоступности seed.
                            // Строгое соблюдение API: на AOSP DataStore отвечает < 100 мс.
                            // Таймаут возможен только при деградации хранилища на кастомной прошивке.
                            // Стратегия: выбрасываем исключение, чтобы вызывающий код мог
                            // информировать пользователя. Fallback на slot-based identity
                            // намеренно исключён: он приведёт к потере привязки данных к SIM
                            // при восстановлении хранилища.
                            throw SeedUnavailableException(slot)
                        }
                    }

                // If not found in cache, try direct resolution
                val resolved =
                    try {
                        // Строгое соблюдение API: when по sealed interface гарантирует
                        // exhaustiveness на этапе компиляции. При добавлении нового варианта
                        // SeedResult компилятор потребует обработать его явно.
                        findActiveIdentityInternal(slot, realSeed)
                    } catch (e: SecurityException) {
                        AppLogger.w("Permission revoked during identity resolution for slot $slot", e)
                        throw SeedUnavailableException(slot)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.e("Identity resolution failed for slot $slot: ${e.javaClass.simpleName}", e)
                        throw SeedUnavailableException(slot)
                    } ?: throw SimNotFoundException(slot)

                manualResolutionCache[slot] = resolved to timeProvider.elapsedRealtime()

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
                migrateSingleIdentity(identity)
            }
        }

        private suspend fun migrateSingleIdentity(identity: SimIdentity) {
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

        /**
         * Поток активных SIM-карт.
         *
         * SharingStarted.Eagerly обеспечивает немедленный старт upstream при создании
         * репозитория, что гарантирует доступность данных при первом обращении.
         *
         * Строгое соблюдение API: на AOSP SubscriptionManager.OnSubscriptionsChangedListener
         * доставляет callback при каждом изменении подписок. Eagerly гарантирует,
         * что ни один callback не будет пропущен из-за остановки upstream.
         *
         * Отклонение кастомной прошивки: если кастомная прошивка не доставляет
         * callback при изменении SIM — это проблема кастомной прошивки.
         */
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
            val realSeed =
                when (seed) {
                    is SeedResult.Real -> seed
                    is SeedResult.Fallback -> {
                        // **Контракт AOSP:** на AOSP генерация seed завершается успешно.
                        //
                        // **Отклонение кастомной прошивки:** Таймаут генерации возможен только при деградации DataStore
                        // на кастомных прошивках. Это проблема кастомной прошивки.
                        //
                        // **Стратегия приложения:** логируется ошибка, возвращается пустой список.
                        AppLogger.e("Cannot fetch active SIMs: identity seed unavailable", null)
                        return emptyList()
                    }
                }

            val seedValue = realSeed.value
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
             *
             * **Строгое соблюдение API:** на AOSP генерация и чтение seed из DataStore
             * занимает миллисекунды. 13 секунд — защитный запас на случай деградации
             * хранилища на кастомной прошивке. Подписчики [seedFlow] будут заблокированы
             * в блоке `onStart` на это время при первом обращении.
             */
            @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
            internal const val SEED_TOTAL_TIMEOUT_MS =
                (Constants.Flow.SEED_GENERATION_TIMEOUT_MS + Constants.Flow.SEED_TOTAL_TIMEOUT_EXTRA_MS)
            private const val MAX_DELAY_MULTIPLIER = 3
            private const val MAX_FETCH_RETRIES = 5
        }
    }
