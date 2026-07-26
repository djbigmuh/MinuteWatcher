package com.example.minutewatcher.core.data

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.minutewatcher.core.domain.SimRepository
import com.example.minutewatcher.core.domain.SettingsRepository
import com.example.minutewatcher.core.model.util.AppLogger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

@HiltWorker
class InitialSetupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val callTracker: CallTracker,
    private val trafficTracker: TrafficTracker,
    private val simRepository: SimRepository,
    private val settingsRepository: SettingsRepository,
    private val migrationService: IdentityMigrationService,
    private val updateCoordinator: UpdateCoordinator,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        try {
            // Perform one-time migration if needed
            val migrationCompleted = settingsRepository.isMigrationV1Completed().first()
            if (!migrationCompleted) {
                val identities = simRepository.getActiveIdentities().first()
                for (i in identities.indices) {
                    migrationService.migrateLegacyData(i, identities[i])
                }
                settingsRepository.setMigrationV1Completed(true)
                AppLogger.d("Migration V1 completed in InitialSetupWorker")
            }

            // Clear stale call session
            callTracker.onBoot()

            // Reset traffic baseline after reboot for active SIMs
            val identities = simRepository.getActiveIdentities().first()
            for (identity in identities) {
                trafficTracker.resetBaseline(identity)
            }

            // Refresh widgets and snapshot
            updateCoordinator.notifyDataChanged(applicationContext)
            
            return Result.success()
        } catch (e: Exception) {
            AppLogger.e("InitialSetupWorker failed", e)
            return Result.retry()
        }
    }
}
