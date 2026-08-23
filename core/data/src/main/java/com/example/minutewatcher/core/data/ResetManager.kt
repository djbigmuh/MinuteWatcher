package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.common.util.catchingCancellation
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.domain.AutoResetUseCase
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.SimUsageResetRepository
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.SimUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.HashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

/**
 * Manages automatic resets of SIM usage stats based on billing cycles.
 *
 * Implementation of [AutoResetUseCase].
 * Safe to call from any coroutine context.
 */
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
        private val lastResetCheck = HashMap<String, Long>()

        /**
         * Checks and performs automatic resets for all active SIMs.
         *
         * @param shouldNotify True if a data change notification should be broadcasted after reset.
         * Safe to call from any coroutine context.
         * @throws java.io.IOException if DataStore operations fail.
         * @throws kotlinx.coroutines.CancellationException if the reset process is cancelled.
         */
        override suspend fun performAutoReset(shouldNotify: Boolean) {
            catchingCancellation(
                block = {
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
                    val nowElapsed = timeProvider.elapsedRealtime()
                    val resetIdentities = mutableSetOf<String>()

                    // Use active SIMs from repository to ensure we don't skip SIMs on stale snapshots
                    val activeSims =
                        simIdentityRepository.observeActiveSims().firstWithTimeout() ?: emptyList()

                    if (activeSims.isEmpty()) {
                        AppLogger.w("ResetManager: No active SIMs detected or timeout during auto-reset check.")
                    }

                    for (simInfo in activeSims) {
                        val identity = simInfo.identity.stableId

                        // Чтение getSimUsage() вынесено за пределы resetMutex.withLock.
                        // Обернуто в таймаут для предотвращения зависания при деградации DataStore.
                        val usage =
                            withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                                simUsageRepository.getSimUsage(identity).first()
                            }

                        if (usage == null) {
                            AppLogger.w(
                                "ResetManager: Timeout reading usage for SIM $identity, skipping reset check.",
                            )
                            continue
                        }

                        val resetPerformed =
                            resetMutex.withLock {
                                checkAndPerformResetForSimInternal(simInfo, now, nowElapsed, usage)
                            }

                        if (resetPerformed) {
                            resetIdentities.add(identity)
                        }
                    }

                    // Clear stale check timestamps for identities that are no longer active
                    resetMutex.withLock {
                        val activeIdentitySet = activeSims.map { it.identity.stableId }.toSet()
                        lastResetCheck.keys.removeAll { it !in activeIdentitySet }
                    }

                    if (resetIdentities.isNotEmpty()) {
                        resetIdentities.forEach { identity ->
                            trafficTracker.resetBaseline(identity)
                        }
                        if (shouldNotify) {
                            dataChangeNotifier.notifyDataChanged()
                        }
                    }
                },
                onError = { e ->
                    AppLogger.e("Error in auto reset: ${e.message}")
                },
            )
        }

        private suspend fun checkAndPerformResetForSimInternal(
            simInfo: com.example.minutewatcher.core.model.ActiveSimInfo,
            now: Long,
            nowElapsed: Long,
            usage: SimUsage,
        ): Boolean {
            val identity = simInfo.identity.stableId

            // Skip if we checked this identity recently (e.g. within the last hour)
            // Access to lastResetCheck is protected by resetMutex in the caller.
            val lastCheck = lastResetCheck[identity]
            if (lastCheck != null && nowElapsed - lastCheck < CHECK_INTERVAL_MS) {
                return false
            }
            lastResetCheck[identity] = nowElapsed

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
