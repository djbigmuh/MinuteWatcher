package com.example.minutewatcher.core.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.minutewatcher.core.domain.AutoResetUseCase
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.MigrationFlagsRepository
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.UsageUseCase
import com.example.minutewatcher.core.model.MigrationSkipReason
import com.example.minutewatcher.core.common.util.AppLogger
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
        private val simIdentityRepository: SimIdentityRepository,
        private val migrationFlagsRepository: MigrationFlagsRepository,
        private val usageStateRepository: UsageStateRepository,
        private val autoResetUseCase: AutoResetUseCase,
        private val usageUseCase: UsageUseCase,
        private val migrationService: IdentityMigrationService,
        private val dataChangeNotifier: DataChangeNotifier,
        private val workScheduler: WorkScheduler,
    ) : CoroutineWorker(appContext, workerParams) {
        override suspend fun doWork(): Result {
            try {
                // Refresh operator metadata first to ensure it's available for migration and UI.
                usageUseCase.refreshOperatorInfo()

                // Always clear stale call sessions first.
                // This does not require phone permission and must not be blocked by migration.
                callTracker.onBoot()

                checkAndPerformMigrations()

                // Perform auto-reset immediately after boot/migration
                val snapshotAfterMigration = usageStateRepository.refresh().snapshot
                autoResetUseCase.performAutoReset(snapshotAfterMigration)

                // Reset traffic baseline after reboot for active SIMs
                val activeSimsForTraffic = simIdentityRepository.observeActiveSims().first()
                activeSimsForTraffic.forEach { info ->
                    trafficTracker.resetBaseline(info.identity.stableId)
                }

                // Final refresh to capture results of auto-reset
                usageStateRepository.refresh()

                // Refresh widgets and snapshot
                dataChangeNotifier.notifyDataChanged(updateWidget = true, force = true)

                // Schedule periodic monitoring if permissions are granted
                if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_PHONE_STATE) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    workScheduler.schedulePeriodicUsageCheck()
                }

                return Result.success()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                AppLogger.w("InitialSetupWorker: Network or storage error, retrying. ${e.message}")
                return Result.retry()
            } catch (e: SecurityException) {
                AppLogger.e("InitialSetupWorker: Permission error (unexpected)", e)
                return Result.failure()
            } catch (e: Exception) {
                AppLogger.e("InitialSetupWorker failed due to unexpected error", e)
                return Result.failure()
            }
        }

        private suspend fun checkAndPerformMigrations() {
            val migrationV1Completed = migrationFlagsRepository.isMigrationV1Completed().first()
            val migrationStabilityCompleted = migrationFlagsRepository.isMigrationIdentityStabilityCompleted().first()

            if (migrationV1Completed && migrationStabilityCompleted) return

            val hasPermission = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.READ_PHONE_STATE,
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasPermission) {
                handleMigrationWithoutPermission(migrationV1Completed)
            } else {
                handleMigrationWithPermission(migrationV1Completed)
            }
        }

        private suspend fun handleMigrationWithoutPermission(v1Completed: Boolean) {
            if (migrationService.hasLegacyData()) {
                AppLogger.d("Migration deferred: permission missing but legacy data exists")
            } else {
                if (!v1Completed) {
                    migrationFlagsRepository.setMigrationV1Completed(true)
                    migrationFlagsRepository.setMigrationV1Skipped(MigrationSkipReason.NO_LEGACY_DATA)
                    AppLogger.d("Migration V1 skipped: no legacy data found")
                }
                migrationFlagsRepository.setMigrationIdentityStabilityCompleted(true)
                migrationService.markSchemaCurrent()
            }
        }

        private suspend fun handleMigrationWithPermission(v1Completed: Boolean) {
            val activeSims = simIdentityRepository.observeActiveSims().first()
            val activeIdentities = activeSims.map { it.identity.stableId }.toSet()

            if (activeSims.isEmpty()) {
                if (migrationService.hasLegacyData(activeIdentities)) {
                    AppLogger.d("Migration deferred: no active SIMs found but legacy data exists")
                } else {
                    if (!v1Completed) {
                        migrationFlagsRepository.setMigrationV1Completed(true)
                        migrationFlagsRepository.setMigrationV1Skipped(MigrationSkipReason.NO_LEGACY_DATA)
                        AppLogger.d("Migration V1 skipped: no active SIMs and no legacy data")
                    }
                    migrationFlagsRepository.setMigrationIdentityStabilityCompleted(true)
                    migrationService.markSchemaCurrent()
                }
            } else {
                activeSims.forEach { info ->
                    migrationService.migrateLegacyData(info.identity)
                }
                migrationFlagsRepository.setMigrationV1Completed(true)
                migrationFlagsRepository.setMigrationIdentityStabilityCompleted(true)
                migrationService.markSchemaCurrent()
                AppLogger.d("Identity migrations completed in InitialSetupWorker")
            }
        }
    }

