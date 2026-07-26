package com.example.minutewatcher.core.data

import android.content.Context
import com.example.minutewatcher.core.domain.SettingsRepository
import com.example.minutewatcher.core.domain.SimRepository
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.util.AppLogger
import com.example.minutewatcher.core.model.util.DateUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ResetManager
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val trafficTracker: TrafficTracker,
        private val updateCoordinator: UpdateCoordinator,
    ) {
        suspend fun performAutoReset(
            simRepository: SimRepository,
            settingsRepository: SettingsRepository,
            snapshot: GlobalSnapshot,
        ) {
            try {
                var anyReset = false
                for (simSnapshot in snapshot.simSnapshots) {
                    if (!simSnapshot.isActive) continue

                    val slot = simSnapshot.slot
                    val identity = simRepository.getIdentity(slot)

                    // Fetch settings for this SIM.
                    // Note: We might still need .first() for the latest settings
                    // UNLESS they are in the snapshot too.
                    // Currently snapshot has usage entities but not auto-reset config.

                    // Let's assume we want to keep configuration out of the usage snapshot
                    // for now, but we can use cached values if we had them.

                    val usage = simRepository.getSimUsage(identity).first()
                    if (usage.autoReset) {
                        if (DateUtils.shouldPerformAutoReset(usage.lastResetDate, usage.autoResetDay)) {
                            simRepository.resetSimUsage(identity)
                            simRepository.setNotifiedLowMinutes(identity, false)
                            simRepository.setNotifiedLowTraffic(identity, false)
                            simRepository.setNotifiedExhaustedMinutes(identity, false)
                            simRepository.setNotifiedExhaustedTraffic(identity, false)
                            anyReset = true
                            AppLogger.d("Auto reset performed for SIM $slot ($identity), day: ${usage.autoResetDay}")
                        }
                    }
                }
                if (anyReset) {
                    for (simSnapshot in snapshot.simSnapshots) {
                        if (simSnapshot.isActive) {
                            trafficTracker.resetBaseline(simRepository.getIdentity(simSnapshot.slot))
                        }
                    }
                    updateCoordinator.notifyDataChanged(context)
                }
            } catch (e: Exception) {
                AppLogger.e("Error in auto reset: ${e.message}")
            }
        }
    }
