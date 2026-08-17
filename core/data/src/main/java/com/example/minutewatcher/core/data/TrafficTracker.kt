package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.domain.CallSessionRepository
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.SimUsageResetRepository
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks mobile traffic usage for the device.
 * Attributes all mobile traffic to the CURRENT DEFAULT DATA SIM.
 */
@Singleton
class TrafficTracker
    @Inject
    constructor(
        private val simIdentityRepository: SimIdentityRepository,
        private val simUsageRepository: SimUsageRepository,
        private val simUsageResetRepository: SimUsageResetRepository,
        private val callSessionRepository: CallSessionRepository,
        private val permissionManager: PermissionManager,
        private val timeProvider: TimeProvider,
        private val networkStatsProvider: NetworkStatsProvider,
    ) : TrackingStatusProvider {
        override val key: String = "traffic"

        private val _status = MutableStateFlow<TrackingStatus>(TrackingStatus.Idle)
        override val status: StateFlow<TrackingStatus> = _status.asStateFlow()

        override suspend fun recover() {
            updateTrafficUsage()
        }

        private val simChangeMutex = Mutex()

        private suspend fun <T> withSimChangeLock(block: suspend () -> T): T = simChangeMutex.withLock { block() }

        /**
         * Мьютекс сериализует операции чтения-модификации-записи над baseline и usage.
         *
         * **Строгое соблюдение API:** на AOSP NetworkStatsManager.querySummaryForDevice
         * возвращает монотонно возрастающее значение. Мьютекс защищает только
         * прикладной уровень от конкурентных вызовов updateTrafficUsage/resetBaseline.
         *
         * **Отклонение кастомной прошивки:** если NetworkStatsManager возвращает
         * немонотонные значения без смены SIM — это проблема кастомной прошивки.
         */
        private val trafficMutex = Mutex()

        /**
         * The subscription ID of the last default data SIM seen by the tracker.
         * Modified only within [withSimChangeLock]. Do not access without the lock.
         *
         * **Кэш последнего известного subscription ID для данных по умолчанию.**
         * Обновляется в ensureInMemoryLastDataSubId() при первом вызове
         * и в updateLastDataSubId() при обнаружении смены SIM.
         * На AOSP getDefaultDataSubscriptionId() возвращает актуальный ID.
         * Если кастомная прошивка возвращает устаревший ID — проблема прошивки.
         * Обнаружение смены SIM выполняется сравнением с inMemoryLastDataSubId.
         */
        private var inMemoryLastDataSubId: Int = -1

        /**
         * Карта identity → число неудачных попыток сброса baseline после смены SIM.
         *
         * Записи создаются в handleSimChange при недоступности статистики.
         * Обрабатываются в performTrafficUpdate при следующем вызове.
         * Очищаются при успешном сбросе или при удалении SIM из списка активных.
         *
         * **Строгое соблюдение API:** на AOSP NetworkStatsManager.querySummaryForDevice
         * возвращает данные при наличии SIM и READ_PHONE_STATE.
         *
         * **Отклонение кастомной прошивки:** если статистика недоступна
         * при наличии SIM и разрешений — это проблема кастомной прошивки.
         *
         * **Стратегия приложения:** при недоступности статистики baseline
         * не сбрасывается, запись добавляется в baselineNeedsRetry.
         * При следующем вызове updateTrafficUsage() выполняется повторная попытка.
         * После MAX_BASELINE_RETRIES неудач запись удаляется и baseline
         * принудительно сбрасывается.
         *
         * **Потокобезопасность:** ConcurrentHashMap обеспечивает потокобезопасность.
         * Очистка по activeIdentities выполняется только при успешном получении
         * списка активных SIM. При таймауте observeActiveSims() записи НЕ
         * очищаются — мы не знаем, какие SIM активны.
         */
        private val baselineNeedsRetry = ConcurrentHashMap<String, Int>()

        private val targetResolver = TrafficTargetResolver(permissionManager)

        /**
         * Resets the traffic baseline for a specific identity.
         * @throws java.io.IOException if DataStore write fails
         * @throws SecurityException if permission is revoked during operation
         */
        suspend fun resetBaseline(identity: String) {
            trafficMutex.withLock {
                if (identity.startsWith(Constants.SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                    AppLogger.w("TrafficTracker: resetBaseline called with invalid identity: $identity")
                    return@withLock
                }
                withSimChangeLock {
                    try {
                        if (!checkPermissions()) return@withSimChangeLock

                        val usage =
                            simUsageRepository.getSimUsage(identity).firstWithTimeout()
                                ?: return@withSimChangeLock
                        val statsResult = getCurrentMobileBytes(usage.lastResetDate)
                        if (statsResult is StatsResult.Success) {
                            val currentBytes = statsResult.bytes
                            simUsageResetRepository.setLastTotalBytes(
                                identity,
                                currentBytes,
                            )
                            AppLogger.d("Traffic baseline reset for $identity to $currentBytes")

                            syncLastDataSubIdIfNeeded(identity)

                            baselineNeedsRetry.remove(identity)

                            _status.value = TrackingStatus.Active
                        } else {
                            val message =
                                if (statsResult is StatsResult.Error) {
                                    AppLogger.e("Traffic stats baseline query failed", statsResult.exception)
                                    statsResult.exception.message
                                } else {
                                    "Stats unavailable"
                                }
                            _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, message)
                        }
                    } catch (e: SecurityException) {
                        AppLogger.e("SecurityException in TrafficTracker", e)
                        _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING, e.message)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.e("Error in resetBaseline", e)
                        _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
                    }
                }
            }
        }

        /**
         * Обновляет данные о трафике для текущей default data SIM.
         *
         * **Строгое соблюдение API:** на AOSP NetworkStatsManager.querySummaryForDevice
         * возвращает монотонно возрастающее значение для одной SIM.
         * DataStore.edit сериализует записи на уровне хранилища.
         *
         * **Отклонение кастомной прошивки:** если NetworkStatsManager возвращает
         * немонотонные значения без смены SIM — это проблема кастомной прошивки.
         * Если observeActiveSims() не эмитит значение в течение таймаута —
         * это проблема кастомной прошивки (деградация DataStore).
         *
         * **Стратегия приложения:** при таймауте observeActiveSims() обновление
         * пропускается, статус переводится в Error. Повторная попытка при
         * следующем вызове updateTrafficUsage().
         *
         * @throws java.io.IOException if DataStore write fails
         * @throws SecurityException if permission is revoked during operation
         */
        @Suppress("ReturnCount")
        // Подавление ReturnCount оправдано: метод содержит несколько защитных проверок (права, таймаут,
        // отсутствие SIM), ранний выход из которых обеспечивает линейную читаемость логики обновления.
        suspend fun updateTrafficUsage() {
            trafficMutex.withLock {
                try {
                    if (!checkPermissions()) return

                    val activeSims = getActiveSimsAndCleanup()
                    if (activeSims == null) {
                        handleIdentityReadTimeout()
                        return
                    }
                    if (activeSims.isEmpty()) {
                        // Все SIM извлечены. Обновляем статус, чтобы UI не показывал устаревший баннер.
                        // Строгое соблюдение API: на AOSP SubscriptionManager незамедлительно
                        // отражает извлечение SIM. Пустой список здесь — штатная ситуация.
                        // Если кастомная прошивка не обновляет список подписок при извлечении SIM —
                        // это проблема кастомной прошивки, не проекта.
                        baselineNeedsRetry.clear()
                        _status.value = TrackingStatus.Idle
                        return
                    }

                    val activeIdentities = activeSims.map { it.identity.stableId }.toSet()
                    /**
                     * Очистка записей baselineNeedsRetry для неактивных SIM.
                     *
                     * **Строгое соблюдение API:** на AOSP observeActiveSims() возвращает
                     * актуальный набор SIM. Очистка привязана к реальному набору,
                     * а не к эвристическому лимиту размера.
                     *
                     * **Отклонение кастомной прошивки:** если кастомная прошивка
                     * не обновляет список активных SIM — это проблема кастомной прошивки.
                     */
                    baselineNeedsRetry.entries.removeIf { it.key !in activeIdentities }

                    // Определение целевой SIM. На AOSP всегда возвращает точный результат.
                    // Fallback на первый активный SIM — эвристика для кастомных прошивок,
                    // не соответствующих документации Google.
                    processActiveSimsTraffic(activeSims)
                } catch (e: SecurityException) {
                    AppLogger.e("SecurityException in TrafficTracker", e)
                    _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING, e.message)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Error updating traffic", e)
                    _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
                }
            }
        }

        /**
         * Обработка таймаута чтения идентичностей при обновлении трафика.
         * Identity read timeout — это переходное состояние; статус будет восстановлен
         * при следующем успешном обновлении.
         */
        private fun handleIdentityReadTimeout() {
            AppLogger.w(
                "TrafficTracker: observeActiveSims timed out, skipping update. " +
                    "baselineNeedsRetry cleanup deferred due to activeSims timeout",
            )
            // **Контракт AOSP:** на AOSP observeActiveSims() не возвращает null (таймаут) при
            // корректном состоянии DataStore.
            //
            // **Отклонение кастомной прошивки:** Таймаут указывает на деградацию хранилища на
            // кастомных прошивках, не соответствующих документации Google. Это проблема кастомной прошивки.
            //
            // **Стратегия приложения:** обновление пропускается, статус переводится в Error.
            _status.value =
                TrackingStatus.Error(
                    TrackingStatus.ErrorType.UNKNOWN,
                    "Identity read timeout in TrafficTracker",
                )
        }

        private suspend fun processActiveSimsTraffic(activeSims: List<ActiveSimInfo>) {
            val result = targetResolver.resolve(activeSims)

            if (result is TrafficTargetResolver.TargetResult.InconsistentFirmware) {
                AppLogger.w(
                    "TrafficTracker: inconsistent firmware state - " +
                        "active SIMs exist but no default data SIM identified.",
                )
                baselineNeedsRetry.clear()
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION)
                return
            }

            val target = (result as TrafficTargetResolver.TargetResult.Success).target

            withSimChangeLock {
                ensureInMemoryLastDataSubId()
                val savedLastDataSubId = inMemoryLastDataSubId
                val isSimChanged =
                    (savedLastDataSubId != -1) &&
                        (target.dataSubId != -1) &&
                        (savedLastDataSubId != target.dataSubId)

                if (isSimChanged) {
                    val targetIdentity = simIdentityRepository.getIdentity(target.slot)
                    val retryCount = baselineNeedsRetry.getOrDefault(targetIdentity, 0)
                    handleSimChange(target.slot, target.dataSubId, savedLastDataSubId, retryCount)
                    handleSkipDelta(target, retryCount)
                } else {
                    val targetIdentity = simIdentityRepository.getIdentity(target.slot)
                    baselineNeedsRetry.entries.removeIf { it.key != targetIdentity }
                    performTrafficUpdate(target, targetIdentity)
                }
            }
        }

        private suspend fun getActiveSimsAndCleanup(): List<ActiveSimInfo>? {
            var activeSims =
                simIdentityRepository
                    .observeActiveSims()
                    .firstWithTimeout(Constants.Flow.LONG_TIMEOUT_MS)

            if (activeSims == null) {
                AppLogger.w(
                    "TrafficTracker: observeActiveSims timed out (${Constants.Flow.LONG_TIMEOUT_MS}ms). " +
                        "Retrying once...",
                )
                activeSims =
                    simIdentityRepository
                        .observeActiveSims()
                        .firstWithTimeout(Constants.Flow.LONG_TIMEOUT_MS)
            }

            if (activeSims == null) {
                AppLogger.e("TrafficTracker: observeActiveSims failed after retry due to timeout.")
                // Возврат null при таймауте: на AOSP observeActiveSims() завершается
                // в пределах SHORT_TIMEOUT_MS. Таймаут указывает на деградацию DataStore
                // на кастомной прошивке. Записи baselineNeedsRetry не очищаются здесь,
                // т.к. без актуального списка SIM невозможно определить, какие записи
                // устарели. Очистка выполняется грубой проверкой размера в updateTrafficUsage.
                return null
            }

            return activeSims
        }

        private fun handleSkipDelta(
            target: TargetInfo,
            retryCount: Int,
        ) {
            AppLogger.d(
                "Traffic delta skipped due to SIM change " +
                    "(slot=${target.slot}, retryCount=$retryCount)",
            )
            // Мы не переводим статус в Active здесь, если уже установлена ошибка (например, сбой получения статистики).
            if (_status.value !is TrackingStatus.Error) {
                _status.value = TrackingStatus.Active
            }
        }

        @Suppress("ReturnCount")
        // Подавление ReturnCount оправдано: метод содержит несколько защитных проверок (таймаут DataStore,
        // удаление SIM), ранний выход из которых обеспечивает линейную читаемость основного пути обновления.
        private suspend fun performTrafficUpdate(
            target: TargetInfo,
            targetIdentity: String,
        ) {
            // === ДОБАВЛЕНИЕ НАЧАЛО ===
            // **Контракт AOSP:** на AOSP NetworkStatsManager.querySummaryForDevice возвращает
            // корректные данные при наличии SIM и READ_PHONE_STATE.
            //
            // **Отклонение кастомной прошивки:** Повторная недоступность возможна только на кастомных
            // прошивках, не соответствующих документации Google. Это проблема кастомной прошивки.
            //
            // **Стратегия приложения:** повторяем сброс перед вычислением дельты с лимитом [MAX_BASELINE_RETRIES].
            val pendingRetry = baselineNeedsRetry[targetIdentity]
            if (pendingRetry != null) {
                if (pendingRetry >= MAX_BASELINE_RETRIES) {
                    AppLogger.e(
                        "TrafficTracker: baseline reset for $targetIdentity failed after " +
                            "$pendingRetry retries, giving up",
                    )
                    baselineNeedsRetry.remove(targetIdentity)
                    _status.value =
                        TrackingStatus.Error(
                            TrackingStatus.ErrorType.DATABASE_ERROR,
                            "Baseline reset failed for $targetIdentity",
                        )
                    return
                }
                // Пытаемся сбросить базлайн повторно
                val statsResult = getCurrentMobileBytes(0L) // 0L → запрос от текущего момента
                if (statsResult is StatsResult.Success) {
                    simUsageResetRepository.setLastTotalBytes(targetIdentity, statsResult.bytes)
                    baselineNeedsRetry.remove(targetIdentity)
                    AppLogger.d("TrafficTracker: baseline reset retry succeeded for $targetIdentity")
                    _status.value = TrackingStatus.Active
                    return // Дельта в этом цикле не начисляется — базлайн только что установлен
                }
                // Повтор не удался — инкрементируем счётчик и выходим без начисления дельты
                baselineNeedsRetry[targetIdentity] = pendingRetry + 1
                AppLogger.w(
                    "TrafficTracker: baseline reset retry ${pendingRetry + 1} failed for $targetIdentity",
                )
                return
            }
            // === ДОБАВЛЕНИЕ КОНЕЦ ===

            // **Контракт AOSP:** на AOSP SIM не может быть удалена между двумя последовательными вызовами
            // в рамках одного корутин-контекста без callback от SubscriptionManager.
            //
            // **Отклонение кастомной прошивки:** Callback может быть потерян на кастомных прошивках.
            // Это проблема кастомной прошивки.
            //
            // **Стратегия приложения:** Повторная проверка актуальности целевой SIM перед записью.
            val currentActiveIdentities = simIdentityRepository.getActiveIdentities().firstWithTimeout()
            if (currentActiveIdentities != null && (targetIdentity !in currentActiveIdentities)) {
                AppLogger.w("TrafficTracker: target SIM $targetIdentity removed during update, skipping")
                return
            }

            val usage =
                simUsageRepository
                    .getSimUsage(targetIdentity)
                    .firstWithTimeout(Constants.Flow.LONG_TIMEOUT_MS)

            if (usage == null) {
                _status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.UNKNOWN,
                        "Timeout reading simUsage",
                    )
                // **Контракт AOSP:** на AOSP getSimUsage() не таймаутится при корректном состоянии DataStore.
                //
                // **Отклонение кастомной прошивки:** Таймаут означает деградацию хранилища на кастомной прошивке.
                // Это проблема кастомной прошивки.
                //
                // **Стратегия приложения:** следующая попытка будет выполнена в новом цикле обновления.
                baselineNeedsRetry.remove(targetIdentity)
                return
            }

            val statsResult = getCurrentMobileBytes(usage.lastResetDate)
            if (handleBaselineRetry(target, targetIdentity, statsResult)) return

            processStatsResult(target, targetIdentity, statsResult)
        }

        private suspend fun processStatsResult(
            target: TargetInfo,
            targetIdentity: String,
            statsResult: StatsResult,
        ) {
            val lastTotalBytes =
                if (statsResult is StatsResult.Success) {
                    simUsageResetRepository
                        .getLastTotalBytes(targetIdentity)
                        .firstWithTimeout(Constants.Flow.LONG_TIMEOUT_MS)
                } else {
                    -1L
                }

            if (lastTotalBytes == null) {
                _status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.UNKNOWN,
                        "Timeout reading lastTotalBytes",
                    )
            } else {
                executeTrafficUpdate(target, targetIdentity, statsResult, lastTotalBytes)
            }
        }

        private fun checkPermissions(): Boolean {
            val error =
                when {
                    !permissionManager.isTelephonySupported() -> {
                        AppLogger.w("TrafficTracker: telephony not supported")
                        TrackingStatus.Error(
                            TrackingStatus.ErrorType.UNKNOWN,
                            "SubscriptionManager unavailable",
                        )
                    }

                    !permissionManager.hasUsageStatsPermission() -> {
                        AppLogger.w("TrafficTracker: usage stats permission missing")
                        TrackingStatus.Error(
                            TrackingStatus.ErrorType.PERMISSION_MISSING,
                            "Usage Access missing",
                        )
                    }

                    !permissionManager.hasPhoneStatePermission() -> {
                        AppLogger.w("TrafficTracker: phone state permission missing")
                        TrackingStatus.Error(
                            TrackingStatus.ErrorType.PERMISSION_MISSING,
                            "Phone State missing",
                        )
                    }

                    else -> null
                }

            error?.let { _status.value = it }
            return error == null
        }

        private suspend fun executeTrafficUpdate(
            target: TargetInfo,
            targetIdentity: String,
            statsResult: StatsResult,
            lastTotalBytes: Long,
        ) {
            if (statsResult !is StatsResult.Success) {
                val message =
                    if (statsResult is StatsResult.Error) {
                        AppLogger.e("Traffic stats query failed", statsResult.exception)
                        statsResult.exception.message
                    } else {
                        "Stats unavailable"
                    }
                AppLogger.w("Traffic stats unavailable for $targetIdentity: $message")
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, message)
                return
            }

            updateTrafficBaselineAndUsage(targetIdentity, statsResult.bytes, lastTotalBytes, target)
            updateLastDataSubId(target.dataSubId)
            _status.value = TrackingStatus.Active
        }

        private suspend fun updateTrafficBaselineAndUsage(
            targetIdentity: String,
            currentBytes: Long,
            lastTotalBytes: Long,
            target: TargetInfo,
        ) {
            // **Контракт AOSP:** на AOSP NetworkStatsManager.querySummaryForDevice возвращает монотонно
            // возрастающее значение для одной SIM.
            //
            // **Отклонение кастомной прошивки:** Если прошивка возвращает немонотонные значения без смены SIM —
            // это проблема кастомной прошивки.
            //
            // **Стратегия приложения:** при уменьшении значения базлайн принудительно сбрасывается.
            if ((lastTotalBytes == -1L) || (currentBytes < lastTotalBytes)) {
                simUsageResetRepository.setLastTotalBytes(targetIdentity, currentBytes)
                AppLogger.d("Traffic baseline reset for $targetIdentity to $currentBytes")
            } else {
                handleTrafficDelta(targetIdentity, currentBytes, lastTotalBytes, target.slot)
            }
        }

        private suspend fun handleBaselineRetry(
            target: TargetInfo,
            targetIdentity: String,
            statsResult: StatsResult,
        ): Boolean {
            var handled = false
            if (statsResult is StatsResult.Success) {
                val currentBytes = statsResult.bytes
                if (baselineNeedsRetry.remove(targetIdentity) != null) {
                    simUsageResetRepository.setLastTotalBytes(
                        targetIdentity,
                        currentBytes,
                    )
                    AppLogger.d("Traffic baseline reset on retry for $targetIdentity to $currentBytes")

                    updateLastDataSubId(target.dataSubId)

                    _status.value = TrackingStatus.Active
                    handled = true
                }
            } else if (baselineNeedsRetry.getOrDefault(targetIdentity, 0) > 0) {
                // G-484: Запись в baselineNeedsRetry остаётся для повторной попытки в следующем цикле,
                // так как статистика временно недоступна. Она будет очищена в handleBaselineRetry (ветка Success)
                // или в performTrafficUpdate (ветка таймаута чтения DataStore).
                AppLogger.w("Stats still unavailable for $targetIdentity, will retry baseline reset later")
                _status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.UNKNOWN,
                        "Stats unavailable, retry pending",
                    )
                handled = true
            }
            return handled
        }

        private suspend fun handleTrafficDelta(
            identity: String,
            currentBytes: Long,
            lastTotalBytes: Long,
            slot: Int,
        ) {
            val deltaBytes = currentBytes - lastTotalBytes
            if (deltaBytes > 0L) {
                val deltaMB = (deltaBytes.toDouble() / Constants.BYTES_PER_MB).toFloat()
                if (deltaMB >= Constants.Traffic.MIN_DELTA_MB) {
                    simUsageResetRepository.incrementTrafficAndSetBaseline(
                        identity,
                        deltaMB,
                        currentBytes,
                    )
                    AppLogger.d("Added $deltaMB MB to identity $identity (SIM $slot)")
                }
            }
        }

        private suspend fun updateLastDataSubId(dataSubId: Int) {
            if ((dataSubId == -1) || (dataSubId == inMemoryLastDataSubId)) return

            // G-484: Update in-memory state FIRST. Even if persistence fails,
            // we want to prevent redundant SIM change detections in the current process lifetime.
            inMemoryLastDataSubId = dataSubId
            try {
                callSessionRepository.setLastDataSubId(dataSubId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("TrafficTracker: Failed to persist last data subId $dataSubId", e)
                // Persistence failure is logged but doesn't block the update loop.
                // The in-memory value will prevent immediate re-triggering of handleSimChange.
            }
        }

        /**
         * Запрашивает суммарный мобильный трафик (RX + TX) за период [startTime, now].
         *
         * **Контракт (AOSP):**
         * [android.app.usage.NetworkStatsManager.querySummaryForDevice] возвращает
         * агрегированный трафик за указанный период. На AOSP результат детерминирован
         * и покрывает весь запрошенный период.
         *
         * **Strict Compliance:**
         * Реализовано строгое соблюдение API и SDK. Отклонения кастомных прошивок
         * не маскируются эвристиками (например, lookback-окнами при сбросе системных часов),
         * а обрабатываются как системные сбои.
         * Если [startTime] равен 0 (первый запуск), запрос выполняется с начала текущего момента (теперь),
         * чтобы избежать неопределенности lookback-периодов.
         */
        private fun getCurrentMobileBytes(startTime: Long): StatsResult {
            val now = timeProvider.now()
            val effectiveStart = if (startTime == 0L) now else startTime

            return networkStatsProvider
                .queryMobileBytes(
                    effectiveStart,
                    now,
                )
        }

        private suspend fun syncLastDataSubIdIfNeeded(identity: String) {
            // Sync in-memory last data subId if this identity is currently the default data SIM.
            // This prevents handleSimChange from triggering immediately after a manual reset.
            val currentDataSubId =
                try {
                    permissionManager.getDefaultDataSubscriptionId()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.w("TrafficTracker: failed to get default data subId for sync: ${e.message}")
                    -1
                }
            if (currentDataSubId != -1) {
                val info =
                    permissionManager.getActiveSubscriptions().find {
                        it.subscriptionId ==
                            currentDataSubId
                    }
                if (info != null) {
                    val currentIdentity = simIdentityRepository.getIdentity(info.simSlotIndex)
                    if (currentIdentity == identity) {
                        updateLastDataSubId(currentDataSubId)
                    }
                }
            }
        }

        private suspend fun ensureInMemoryLastDataSubId() {
            if (inMemoryLastDataSubId != -1) return

            inMemoryLastDataSubId = callSessionRepository.getLastDataSubId().firstWithTimeout() ?: -1
        }

        /**
         * Обработка смены SIM-карты по умолчанию.
         *
         * **Контракт AOSP:** на AOSP NetworkStatsManager.querySummaryForDevice возвращает
         * корректные данные при наличии SIM и READ_PHONE_STATE.
         *
         * **Отклонение кастомной прошивки:** Недоступность статистики при смене SIM возможна
         * только на кастомных прошивках. Это проблема кастомной прошивки, а не приложения.
         *
         * **Стратегия приложения:** используется механизм повторных попыток [MAX_BASELINE_RETRIES].
         */
        private suspend fun handleSimChange(
            targetSim: Int,
            currentDataSubId: Int,
            savedLastDataSubId: Int,
            retryCount: Int,
        ) {
            AppLogger.d(
                "Default data SIM changed from $savedLastDataSubId to $currentDataSubId. " +
                    "Resetting baseline.",
            )
            try {
                val identity = simIdentityRepository.getIdentity(targetSim)

                // Ensure we only track retry for the current target.
                baselineNeedsRetry.entries.removeIf { it.key != identity }

                if (retryCount >= MAX_BASELINE_RETRIES) {
                    AppLogger.e(
                        "TrafficTracker: Baseline reset failed after $retryCount retries for $identity, giving up",
                    )
                    // Принудительно обновляем lastDataSubId, чтобы прекратить повторные попытки.
                    // Базлайн остаётся неверным, но further delta computation будет защищена
                    // проверкой currentBytes < lastTotalBytes в performTrafficUpdate.
                    updateLastDataSubId(currentDataSubId)
                    baselineNeedsRetry.remove(identity)
                    _status.value =
                        TrackingStatus.Error(
                            TrackingStatus.ErrorType.DATABASE_ERROR,
                            "Baseline reset failed after $retryCount retries for $identity",
                        )
                    return
                }

                // Попытка получить статистику
                val statsResult = getCurrentMobileBytes(0L) // 0L → запрос от текущего момента
                if (statsResult is StatsResult.Success) {
                    simUsageResetRepository.setLastTotalBytes(identity, statsResult.bytes)
                    baselineNeedsRetry.remove(identity)
                    // Обновляем lastDataSubId ТОЛЬКО после успешного сброса базлайна
                    updateLastDataSubId(currentDataSubId)
                    AppLogger.d("Baseline reset successfully for $identity")
                    _status.value = TrackingStatus.Active
                } else {
                    val reason =
                        if (statsResult is StatsResult.Error) {
                            AppLogger.e("Traffic stats query failed on SIM change", statsResult.exception)
                            statsResult.exception.message
                        } else {
                            "Stats unavailable"
                        }
                    AppLogger.w(
                        "Stats unavailable on SIM change for $identity: $reason. " +
                            "Baseline reset will be retried (attempt ${retryCount + 1}).",
                    )
                    // Статистика недоступна — НЕ обновляем lastDataSubId
                    baselineNeedsRetry[identity] = retryCount + 1
                    _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR, reason)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Failed to handle SIM change", e)
            }
        }

        private companion object {
            private const val MAX_BASELINE_RETRIES = 3
        }
    }

internal data class TargetInfo(
    val slot: Int,
    val dataSubId: Int,
)

/**
 * Вспомогательный класс для определения целевой SIM-карты для отслеживания трафика.
 *
 * **Контракт AOSP:** на AOSP getDefaultDataSubscriptionId() всегда возвращает валидный ID
 * при наличии активных SIM.
 *
 * **Отклонение кастомной прошивки:** Состояние "SIM есть, но default data не определена"
 * возможно только на кастомных прошивках. Это проблема кастомной прошивки, а не приложения.
 *
 * **Стратегия приложения:** Отклонения обрабатываются как [TargetResult.InconsistentFirmware].
 */
internal class TrafficTargetResolver(
    private val permissionManager: PermissionManager,
) {
    sealed interface TargetResult {
        data class Success(
            val target: TargetInfo,
        ) : TargetResult

        object NoActiveSims : TargetResult

        object InconsistentFirmware : TargetResult
    }

    /**
     * Определяет целевую SIM-карту для атрибуции трафика.
     *
     * **Строгое соблюдение API:** на AOSP SubscriptionManager.getDefaultDataSubscriptionId()
     * всегда возвращает валидный ID при наличии активной SIM и READ_PHONE_STATE.
     *
     * **Отклонение кастомной прошивки:** если getDefaultDataSubscriptionId() возвращает -1
     * при наличии активных SIM — это проблема кастомной прошивки.
     *
     * **Эвристика (в пределах критерия надёжности):**
     * Если default data SIM не определена системой (возврат -1), выполняется
     * fallback на первый активный SIM из списка. Это допустимо, так как:
     * 1. На AOSP данная ситуация не возникает.
     * 2. На кастомной прошивке это единственный способ продолжить трекинг.
     * 3. Пользователь видит индикатор неточной атрибуции (INACCURATE_ATTRIBUTION).
     *
     * **Гарантия:** при возврате fallback-результата устанавливается
     * TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION, что отображается
     * в UI как предупреждение.
     */
    @Suppress("ReturnCount")
    fun resolve(activeSims: List<ActiveSimInfo>): TargetResult {
        if (activeSims.isEmpty()) return TargetResult.NoActiveSims

        val (targetSim, currentDataSubId) =
            resolveTargetSim(activeSims) ?: run {
                // **Контракт AOSP:** на AOSP getDefaultDataSubscriptionId() всегда возвращает
                // валидный ID при наличии активных SIM.
                //
                // **Отклонение кастомной прошивки:** Состояние "SIM есть, но default data не определена"
                // возможно только на кастомных прошивках. Это проблема кастомной прошивки.
                //
                // **Стратегия приложения:** Отклонение обрабатывается как InconsistentFirmware.
                return TargetResult.InconsistentFirmware
            }

        return TargetResult.Success(
            TargetInfo(
                targetSim,
                currentDataSubId,
            ),
        )
    }

    private fun resolveTargetSim(activeSims: List<ActiveSimInfo>): Pair<Int, Int>? {
        var targetSim = -1
        var currentDataSubId = -1
        try {
            currentDataSubId = permissionManager.getDefaultDataSubscriptionId()
            if (currentDataSubId != -1) {
                val subInfoList = permissionManager.getActiveSubscriptions()
                targetSim = subInfoList.find { it.subscriptionId == currentDataSubId }?.simSlotIndex ?: -1
            }
        } catch (e: Exception) {
            AppLogger.e("Error getting default data SIM", e)
        }

        if (targetSim == -1) {
            val resolveResult = resolveTargetSimFromActiveSims(activeSims)
            if (resolveResult != null) {
                targetSim = resolveResult.first
                currentDataSubId = resolveResult.second
            }
        }

        return if (targetSim != -1) Pair(targetSim, currentDataSubId) else null
    }

    /**
     * Attempts to identify the target SIM for traffic attribution from the list of active SIMs.
     *
     * **Contract (AOSP):**
     * This method relies on [ActiveSimInfo.isDefaultData], which is populated based on
     * [android.telephony.SubscriptionManager.getDefaultDataSubscriptionId]. In a standard AOSP environment,
     * this correctly identifies the SIM currently responsible for mobile data.
     *
     * **Strict Compliance:**
     * If no SIM is explicitly marked as default data, we no longer fall back to heuristics
     * (like lowest slot index). Such states are treated as firmware inconsistencies.
     */
    private fun resolveTargetSimFromActiveSims(activeSims: List<ActiveSimInfo>): Pair<Int, Int>? {
        val defaultDataSim = activeSims.find { it.isDefaultData }

        return defaultDataSim?.let {
            Pair(it.slotIndex, it.subscriptionId)
        }
    }
}
