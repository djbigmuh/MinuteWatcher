package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.domain.AutoResetUseCase
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.SimUsageResetRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        private val resetMutex = Mutex()
        private val lastResetCheck = mutableMapOf<String, Long>()

        override suspend fun performAutoReset(shouldNotify: Boolean) {
            resetMutex.withLock {
                try {
                    val now = timeProvider.now()
                    val resetIdentities = mutableSetOf<String>()

                    // Use active SIMs from repository to ensure we don't skip SIMs on stale snapshots
                    val activeSims = simIdentityRepository.observeActiveSims().first()

                    for (simInfo in activeSims) {
                        val slot = simInfo.slotIndex
                        val identity = simInfo.identity.stableId

                        // Skip if we checked this identity recently (e.g. within the last hour)
                        val lastCheck = lastResetCheck[identity] ?: 0L
                        if (now - lastCheck < CHECK_INTERVAL_MS) continue

                        // Update last check time early to avoid redundant checks even if not due or disabled
                        lastResetCheck[identity] = now

                        val usage = simUsageRepository.getSimUsage(identity).first()
                        if (usage.autoReset) {
                            val reset =
                                simUsageResetRepository.resetSimUsageIfDue(
                                    identity,
                                    usage.autoResetDay,
                                    now,
                                )
                            if (reset) {
                                resetIdentities.add(identity)
                                AppLogger.d(
                                    "Auto reset performed for SIM $slot ($identity), day: ${usage.autoResetDay}",
                                )
                            }
                        }
                    }
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

        companion object {
            private val CHECK_INTERVAL_MS = 1.hours.inWholeMilliseconds
        }
    }
