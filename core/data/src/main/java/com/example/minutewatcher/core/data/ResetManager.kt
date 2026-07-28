package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.domain.SimRepository
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.util.AppLogger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ResetManager
    @Inject
    constructor(
        private val trafficTracker: TrafficTracker,
        private val updateCoordinator: UpdateCoordinator,
    ) {
        private val resetMutex = Mutex()

        suspend fun performAutoReset(
            simRepository: SimRepository,
            snapshot: GlobalSnapshot,
        ) {
            resetMutex.withLock {
                try {
                    val resetIdentities = mutableSetOf<String>()
                    for (simSnapshot in snapshot.simSnapshots) {
                        if (!simSnapshot.isActive) continue

                        val slot = simSnapshot.slot
                        val identity = simRepository.getIdentity(slot)

                        val usage = simRepository.getSimUsage(identity).first()
                        if (usage.autoReset) {
                            val reset =
                                simRepository.resetSimUsageIfDue(
                                    identity,
                                    usage.autoResetDay,
                                    System.currentTimeMillis(),
                                )
                            if (reset) {
                                resetIdentities.add(identity)
                                AppLogger.d("Auto reset performed for SIM $slot ($identity), day: ${usage.autoResetDay}")
                            }
                        }
                    }
                    if (resetIdentities.isNotEmpty()) {
                        resetIdentities.forEach { identity ->
                            trafficTracker.resetBaseline(identity)
                        }
                        updateCoordinator.notifyDataChanged()
                    }
                } catch (e: Exception) {
                    AppLogger.e("Error in auto reset: ${e.message}")
                }
            }
        }
    }
