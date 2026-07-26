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
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.model.MigrationSkipReason
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
        private val usageStateRepository: UsageStateRepository,
        private val migrationService: IdentityMigrationService,
        private val updateCoordinator: UpdateCoordinator,
    ) : CoroutineWorker(appContext, workerParams) {
        override suspend fun doWork(): Result {
            try {
                // Always clear stale call sessions first.
                // This does not require phone permission and must not be blocked by migration.
                callTracker.onBoot()

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
                            if (runAttemptCount > 5) {
                                AppLogger.w("Migration V1 skipped after retries: permission missing but legacy data exists")
                                settingsRepository.setMigrationV1Completed(true)
                                settingsRepository.setMigrationV1Skipped(MigrationSkipReason.NO_PERMISSION)
                                return Result.success()
                            }

                            AppLogger.d("Migration V1 deferred: permission missing but legacy data exists")
                            return Result.retry()
                        } else {
                            // No legacy data, we can safely mark as completed
                            settingsRepository.setMigrationV1Completed(true)
                            settingsRepository.setMigrationV1Skipped(MigrationSkipReason.NO_LEGACY_DATA)
                            AppLogger.d("Migration V1 skipped: no legacy data found")
                        }
                    } else {
                        val activeSims = simRepository.observeActiveSims().first()

                        if (activeSims.isEmpty()) {
                            if (migrationService.hasLegacyData() && runAttemptCount <= 5) {
                                AppLogger.d("Migration V1 deferred: no active SIMs found")
                                return Result.retry()
                            }

                            if (!migrationService.hasLegacyData()) {
                                settingsRepository.setMigrationV1Completed(true)
                                settingsRepository.setMigrationV1Skipped(MigrationSkipReason.NO_LEGACY_DATA)
                                AppLogger.d("Migration V1 skipped: no legacy data found")
                            } else {
                                AppLogger.w("Migration V1 skipped after retries: no active SIMs found")
                                settingsRepository.setMigrationV1Completed(true)
                                settingsRepository.setMigrationV1Skipped(MigrationSkipReason.NO_ACTIVE_SIM)
                            }
                        } else {
                            activeSims.forEach { info ->
                                migrationService.migrateLegacyData(
                                    info.slotIndex,
                                    info.identity.stableId,
                                )
                            }
                            settingsRepository.setMigrationV1Completed(true)
                            AppLogger.d("Migration V1 completed in InitialSetupWorker")
                        }
                    }
                }

                // Reset traffic baseline after reboot for active SIMs
                val activeSimsForTraffic = simRepository.observeActiveSims().first()
                activeSimsForTraffic.forEach { info ->
                    trafficTracker.resetBaseline(info.identity.stableId)
                }

                // Refresh state and return updated snapshot
                usageStateRepository.refresh()

                // Refresh widgets and snapshot
                updateCoordinator.notifyDataChanged(updateWidget = true, force = true)

                return Result.success()
            } catch (e: Exception) {
                AppLogger.e("InitialSetupWorker failed", e)
                return Result.retry()
            }
        }
    }
