package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.domain.AutoResetUseCase
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.SimUsageResetRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.hours

@Singleton
class ResetManager
    @Inject
    constructor(
        private val simIdentityRepository: SimIdentityRepository,
        private val simUsageRepository: SimUsageRepository,
        private val simUsageResetRepository: SimUsageResetRepository,
        private val trafficTracker: TrafficTracker,
        private val dataChangeNotifier: DataChangeNotifier,
        private val timeProvider: TimeProvider,
    ) : AutoResetUseCase {
        /**
         * High-level mutex to coordinate the overall auto-reset process across all SIMs.
         * This complements the lower-level [SimUsageRepositoryImpl.resetMutex] which ensures
         * atomicity for individual SIM reset operations.
         */
        private val resetMutex = Mutex()
        private val lastResetCheck = ConcurrentHashMap<String, Long>()

        override suspend fun performAutoReset(shouldNotify: Boolean) {
            resetMutex.withLock {
                try {
                    /**
                     * Использование wall-clock (now) для расчёта даты сброса.
                     * Авто-сброс лимитов — это календарная операция, привязанная к биллинговому
                     * периоду оператора. Поэтому расчёт "наступил ли день сброса" должен
                     * опираться на текущую системную дату.
                     *
                     * Ожидаемое поведение: при ручном переводе системных часов пользователем
                     * календарный сброс сработает в соответствии с новой датой. Это не является
                     * багом, так как приложение синхронизирует состояние с текущим системным
                     * временем, которое для него является истинным календарным временем.
                     */
                    val now = timeProvider.now()
                    val resetIdentities = mutableSetOf<String>()

                    // Use active SIMs from repository to ensure we don't skip SIMs on stale snapshots
                    val activeSims = simIdentityRepository.observeActiveSims().firstWithTimeout() ?: emptyList()

                    if (activeSims.isEmpty()) {
                        AppLogger.w("ResetManager: No active SIMs detected or timeout during auto-reset check.")
                    }

                    for (simInfo in activeSims) {
                        if (checkAndPerformResetForSim(simInfo, now)) {
                            resetIdentities.add(simInfo.identity.stableId)
                        }
                    }

                    // Clear stale check timestamps for identities that are no longer active
                    val activeIdentitySet = activeSims.map { it.identity.stableId }.toSet()
                    lastResetCheck.keys.removeAll { it !in activeIdentitySet }

                    if (resetIdentities.isNotEmpty()) {
                        resetIdentities.forEach { identity ->
                            trafficTracker.resetBaseline(identity)
                        }
                        if (shouldNotify) {
                            dataChangeNotifier.notifyDataChanged()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Error in auto reset: ${e.message}")
                }
            }
        }

        private suspend fun checkAndPerformResetForSim(
            simInfo: com.example.minutewatcher.core.model.ActiveSimInfo,
            now: Long,
        ): Boolean {
            val identity = simInfo.identity.stableId

            // Skip if we checked this identity recently (e.g. within the last hour)
            // Using compute to make check-and-update atomic.
            var wasUpdated = false
            lastResetCheck.compute(identity) { _, lastCheck ->
                if (lastCheck == null || now - lastCheck >= CHECK_INTERVAL_MS) {
                    wasUpdated = true
                    now
                } else {
                    lastCheck
                }
            }

            if (!wasUpdated) return false

            val usage = simUsageRepository.getSimUsage(identity).first()
            var result = false
            if (usage.autoReset) {
                val reset =
                    simUsageResetRepository.resetSimUsageIfDue(
                        identity,
                        usage.autoResetDay,
                        now,
                    )
                if (reset) {
                    AppLogger.d(
                        "Auto reset performed for SIM ${simInfo.slotIndex} ($identity), day: ${usage.autoResetDay}",
                    )
                    result = true
                }
            }
            return result
        }

        companion object {
            private val CHECK_INTERVAL_MS = 1.hours.inWholeMilliseconds
        }
    }
