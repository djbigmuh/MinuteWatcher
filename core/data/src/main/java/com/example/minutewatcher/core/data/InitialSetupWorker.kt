package com.example.minutewatcher.core.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.minutewatcher.core.domain.SettingsRepository
import com.example.minutewatcher.core.domain.SimRepository
import com.example.minutewatcher.core.model.util.AppLogger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

@HiltWorker
class InitialSetupWorker
    @AssistedInject
    constructor(
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
                    val hasPermission =
                        ContextCompat.checkSelfPermission(
                            applicationContext,
                            Manifest.permission.READ_PHONE_STATE,
                        ) == PackageManager.PERMISSION_GRANTED

                    if (!hasPermission) {
                        if (migrationService.hasLegacyData()) {
                            AppLogger.d("Migration V1 deferred: permission missing but legacy data exists")
                            return Result.retry()
                        } else {
                            // No legacy data, we can safely mark as completed
                            settingsRepository.setMigrationV1Completed(true)
                            AppLogger.d("Migration V1 skipped: no legacy data found")
                        }
                    } else {
                        val identities = simRepository.getActiveIdentities().first()
                        if (identities.isEmpty()) {
                            AppLogger.d("Migration V1 deferred: no active identities found")
                            return Result.retry()
                        }

                        for (i in identities.indices) {
                            migrationService.migrateLegacyData(i, identities[i])
                        }
                        settingsRepository.setMigrationV1Completed(true)
                        AppLogger.d("Migration V1 completed in InitialSetupWorker")
                    }
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
