package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.domain.AutoResetUseCase
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.model.GlobalSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ResetManager
    @Inject
    constructor(
        private val simUsageRepository: SimUsageRepository,
        private val trafficTracker: TrafficTracker,
        private val dataChangeNotifier: DataChangeNotifier,
        private val timeProvider: TimeProvider,
    ) : AutoResetUseCase {
        private val resetMutex = Mutex()

        override suspend fun performAutoReset(snapshot: GlobalSnapshot) {
            resetMutex.withLock {
                try {
                    val resetIdentities = mutableSetOf<String>()
                    for (simSnapshot in snapshot.simSnapshots) {
                        if (!simSnapshot.isActive) continue

                        val slot = simSnapshot.slot
                        val identity = simSnapshot.identity

                        val usage = simUsageRepository.getSimUsage(identity).first()
                        if (usage.autoReset) {
                            val reset =
                                simUsageRepository.resetSimUsageIfDue(
                                    identity,
                                    usage.autoResetDay,
                                    timeProvider.now(),
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
                        dataChangeNotifier.notifyDataChanged()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Error in auto reset: ${e.message}")
                }
            }
        }
    }
