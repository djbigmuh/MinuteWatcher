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
import com.example.minutewatcher.core.model.SimUsage
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
         * Мьютекс сериализует конкурентные вызовы [updateTrafficUsage].
         * Без него два параллельных воркера прочитают одинаковый lastTotalBytes
         * и дважды прибавят дельту к trafficUsedMb.
         * Строгое соблюдение API: на AOSP NetworkStatsManager.querySummaryForDevice
         * детерминирован для одного и того же интервала, поэтому сериализация
         * полностью устраняет гонку.
         */
        private val trafficUpdateMutex = Mutex()

        /**
         * The subscription ID of the last default data SIM seen by the tracker.
         * Modified only within [withSimChangeLock]. Do not access without the lock.
         */
        private var inMemoryLastDataSubId: Int = -1

        /**
         * Карта identity -> число неудачных попыток сброса baseline после смены SIM.
         * Записи создаются в [handleSimChange] при недоступности статистики.
         * Очищаются в [performTrafficUpdate] при успешном обновлении или таймауте.
         * Также очищаются в [processActiveSimsTraffic] для нецелевых identity.
         * На AOSP смена SIM всегда сопровождается доступной статистикой;
         * накопление записей возможно только на кастомных прошивках.
         */
        private val baselineNeedsRetry = ConcurrentHashMap<String, Int>()

        private val targetResolver = TrafficTargetResolver(permissionManager)

        /**
         * Resets the traffic baseline for a specific identity.
         * @throws java.io.IOException if DataStore write fails
         * @throws SecurityException if permission is revoked during operation
         */
        suspend fun resetBaseline(identity: String) {
            if (identity.startsWith(Constants.SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("TrafficTracker: resetBaseline called with invalid identity: $identity")
                return
            }
            trafficUpdateMutex.withLock {
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
         * Checks and updates traffic usage for the current active SIM.
         * @throws java.io.IOException if DataStore write fails
         * @throws SecurityException if permission is revoked during operation
         */
        suspend fun updateTrafficUsage() {
            trafficUpdateMutex.withLock {
                try {
                    if (!checkPermissions()) return

                    ensureBaselineNeedsRetryCapacity()

                    val activeSims = getActiveSimsAndCleanup()
                    if (activeSims == null) {
                        handleIdentityReadTimeout()
                        return
                    }

                    val identities =
                        activeSims
                            .asSequence()
                            .map { it.identity.stableId }
                            .toSet()
                    cleanupStaleRetryEntries(identities)

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
         * Защита от неограниченного роста baselineNeedsRetry.
         * Это аварийный предохранитель, а не штатный механизм очистки. Штатная очистка —
         * cleanupStaleRetryEntries при успешном получении списка SIM.
         *
         * Строгое соблюдение API: на AOSP observeActiveSims() не возвращает null
         * (timeout) при наличии активного SubscriptionManager и READ_PHONE_STATE.
         * Таймаут возможен только при деградации DataStore на кастомных прошивках,
         * не соответствующих документации Google.
         *
         * Стратегия: если количество записей превышает удвоенное число поддерживаемых
         * слотов (MAX_SUPPORTED_SIM_SLOTS * 2), карта очищается полностью.
         */
        private fun ensureBaselineNeedsRetryCapacity() {
            if (baselineNeedsRetry.size > (Constants.SimConstants.MAX_SUPPORTED_SIM_SLOTS * 2)) {
                AppLogger.w(
                    "TrafficTracker: baselineNeedsRetry map size exceeded limit, " +
                        "force clearing to prevent leak.",
                )
                baselineNeedsRetry.clear()
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
            // Записи baselineNeedsRetry не очищаются при таймауте observeActiveSims,
            // поскольку без актуального списка SIM невозможно определить, какие записи устарели.
            // Очистка выполняется:
            //   1. При успешном вызове updateTrafficUsage (cleanupStaleRetryEntries).
            //   2. Грубой проверкой ёмкости (ensureBaselineNeedsRetryCapacity) как аварийный предохранитель.
            // Строгое соблюдение API: на AOSP observeActiveSims() не возвращает null (таймаут)
            // при корректном состоянии DataStore. Таймаут возможен только при деградации
            // хранилища на кастомных прошивках, не соответствующих документации Google.
            _status.value =
                TrackingStatus.Error(
                    TrackingStatus.ErrorType.UNKNOWN,
                    "Identity read timeout in TrafficTracker",
                )
        }

        private suspend fun processActiveSimsTraffic(activeSims: List<ActiveSimInfo>) {
            val result = targetResolver.resolve(activeSims)

            if (result is TrafficTargetResolver.TargetResult.NoActiveSims) {
                AppLogger.d("TrafficTracker: no active SIM found, setting Idle")
                baselineNeedsRetry.clear()
                _status.value = TrackingStatus.Idle
                return
            }

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

        /**
         * Cleans up [baselineNeedsRetry] by removing entries for SIMs that are no longer active.
         * This is only called when active SIM list is successfully retrieved to avoid
         * clearing entries during transient timeout states.
         */
        private fun cleanupStaleRetryEntries(activeIdentities: Set<String>) {
            baselineNeedsRetry.entries.removeIf { entry -> entry.key !in activeIdentities }
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

        private suspend fun performTrafficUpdate(
            target: TargetInfo,
            targetIdentity: String,
        ) {
            // Повторная проверка актуальности целевой SIM перед записью.
            // Строгое соблюдение API: на AOSP SIM не может быть удалена между
            // двумя последовательными вызовами в рамках одного корутин-контекста
            // без callback от SubscriptionManager. Однако на кастомных прошивках
            // callback может быть потерян. Повторная проверка — защита от гонки
            // между observation flow и записью данных.
            val currentActiveIdentities = simIdentityRepository.getActiveIdentities().firstWithTimeout()
            if (currentActiveIdentities != null && targetIdentity !in currentActiveIdentities) {
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
                // Таймаут чтения simUsage не повторяется немедленно, в отличие от
                // handleBaselineRetry для статистики. Обоснование:
                //   - simUsage читается из DataStore, который на AOSP отвечает < 100 мс.
                //   - Таймаут означает деградацию хранилища на кастомной прошивке.
                //   - Следующий вызов updateTrafficUsage (через 15 мин или по событию)
                //     повторит попытку автоматически.
                //   - Немедленный повтор бесполезен: если DataStore не отвечает,
                //     повторный запрос тоже не ответит.
                // Строгое соблюдение API: на AOSP getSimUsage() не таймаутится
                // при корректном состоянии DataStore. Это проблема кастомной прошивки.
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
            // Строгое соблюдение API: NetworkStatsManager.querySummaryForDevice
            // возвращает монотонно возрастающее значение на AOSP.
            // Уменьшение currentBytes относительно lastTotalBytes означает
            // одно из двух:
            // 1. Перезагрузка устройства — счётчик сброшен.
            // 2. Смена SIM по умолчанию — новый счётчик для новой SIM.
            // В обоих случаях корректно сбросить baseline на текущее значение.
            // На кастомных прошивках, возвращающих немонотонные значения,
            // это проблема кастомной прошивки, а не штатного пути.
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

        private suspend fun resetStatsAfterSimChange(
            identity: String,
            statsResult: StatsResult,
        ) {
            if (statsResult is StatsResult.Success) {
                val currentBytes = statsResult.bytes
                simUsageResetRepository.setLastTotalBytes(
                    identity,
                    currentBytes,
                )
                AppLogger.d("Baseline reset successfully for $identity")
            }
        }

        /**
         * Handles default data SIM change.
         *
         * Scenarios:
         * 1. Success: Current mobile stats are available. We reset the baseline to the current value
         *    and update [inMemoryLastDataSubId]. Future updates will track delta from this point.
         * 2. Retry: Stats are temporarily unavailable (e.g., system busy). We record the attempt
         *    in [baselineNeedsRetry]. The next [updateTrafficUsage] call will retry the reset.
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

                val shouldReturn =
                    handleSimChangeFailure(
                        identity,
                        StatsResult.Unavailable,
                        retryCount,
                        isLimitCheckOnly = true,
                    )
                if (shouldReturn) {
                    updateLastDataSubId(currentDataSubId)
                    return
                }

                val usage = simUsageRepository.getSimUsage(identity).firstWithTimeout()
                val statsResult = fetchStatsOnSimChange(usage)

                if (statsResult is StatsResult.Success) {
                    resetStatsAfterSimChange(identity, statsResult)
                    baselineNeedsRetry.remove(identity)
                    // Update subId ONLY after successful baseline reset to ensure no delta is lost
                    updateLastDataSubId(currentDataSubId)
                } else {
                    handleSimChangeFailure(identity, statsResult, retryCount)
                    updateLastDataSubId(currentDataSubId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Failed to handle SIM change", e)
            }
        }

        /**
         * Обрабатывает сбой при смене SIM: фиксирует попытку в baselineNeedsRetry.
         * Строгое соблюдение API: повторная попытка сброса baseline выполняется
         * при следующем вызове updateTrafficUsage. На AOSP статистика доступна
         * сразу после смены SIM; задержка возможна только на кастомных прошивках.
         */
        private fun handleSimChangeFailure(
            identity: String,
            statsResult: StatsResult,
            retryCount: Int,
            isLimitCheckOnly: Boolean = false,
        ): Boolean =
            when {
                retryCount >= MAX_BASELINE_RETRIES -> {
                    // Строгое соблюдение API: на AOSP NetworkStatsManager.querySummaryForDevice
                    // возвращает корректные данные при наличии SIM и READ_PHONE_STATE.
                    // Повторные неудачи возможны только при деградации системных сервисов
                    // на кастомных прошивках, не соответствующих документации Google.
                    // Лимит повторных попыток предотвращает бесконечный цикл.
                    AppLogger.e("TrafficTracker: Baseline reset failed after $retryCount retries for $identity")
                    _status.value =
                        TrackingStatus.Error(
                            TrackingStatus.ErrorType.DATABASE_ERROR,
                            "Baseline reset failed after $retryCount retries for $identity",
                        )
                    baselineNeedsRetry.remove(identity)
                    true
                }

                isLimitCheckOnly -> false

                else -> {
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
                    // Мы не форсируем сброс (-1L), а сохраняем состояние ошибки.
                    _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR, reason)
                    baselineNeedsRetry[identity] = retryCount + 1
                    true
                }
            }

        private fun fetchStatsOnSimChange(usage: SimUsage?): StatsResult =
            if (usage != null) {
                try {
                    getCurrentMobileBytes(usage.lastResetDate)
                } catch (e: Exception) {
                    StatsResult.Error(e)
                }
            } else {
                StatsResult.Unavailable
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
 * Вынесен из [TrafficTracker] для упрощения основного класса.
 *
 * Реализовано строгое соблюдение API и SDK. Отклонения кастомных прошивок
 * (например, отсутствие defaultDataSubscriptionId при наличии активных SIM)
 * не маскируются, а обрабатываются как [TargetResult.InconsistentFirmware].
 * Если кастомная прошивка не соответствует документации Google —
 * это проблема кастомной прошивки.
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

    // Подавление ReturnCount оправдано: 3 точки выхода соответствуют спецификации TargetResult
    // (отсутствие SIM, некорректное состояние прошивки, успех).
    @Suppress("ReturnCount")
    fun resolve(activeSims: List<ActiveSimInfo>): TargetResult {
        if (activeSims.isEmpty()) return TargetResult.NoActiveSims

        val (targetSim, currentDataSubId) =
            resolveTargetSim(activeSims) ?: run {
                // Строгое соблюдение API: на AOSP getDefaultDataSubscriptionId() всегда
                // возвращает валидный ID при наличии активных SIM.
                // Состояние "SIM есть, но default data не определена" возможно только
                // на кастомных прошивках, не соответствующих документации Google.
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
