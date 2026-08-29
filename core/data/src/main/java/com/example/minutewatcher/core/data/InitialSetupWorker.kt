package com.example.minutewatcher.core.data

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.MigrationSkipReason
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.io.IOException

@HiltWorker
class InitialSetupWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted workerParams: WorkerParameters,
        private val deps: InitialSetupWorkerDependencies,
    ) : CoroutineWorker(appContext, workerParams) {
        override suspend fun doWork(): Result {
            try {
                // Refresh operator metadata first to ensure it's available for migration and UI.
                deps.usageUseCase.refreshOperatorInfo()

                // Always clear stale call sessions first.
                // This does not require phone permission and must not be blocked by migration.
                deps.callTracker.onBoot()

                val migrationResult = checkAndPerformMigrations()
                if (migrationResult != null) return migrationResult

                // Perform auto-reset immediately after boot/migration. Skip internal notify as we do it at the end.
                try {
                    deps.autoResetUseCase.performAutoReset(shouldNotify = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    AppLogger.w("InitialSetupWorker: Auto-reset failed with IO error, retrying. ${e.message}")
                    return Result.retry()
                } catch (e: Exception) {
                    AppLogger.w("InitialSetupWorker: Auto-reset failed with non-critical error, continuing.", e)
                }

                // Reset traffic baseline after reboot for the current default data SIM
                val activeSimsForTraffic = getActiveSimsWithRetry()
                val defaultDataSubId = deps.permissionManager.getDefaultDataSubscriptionId()
                activeSimsForTraffic
                    .filter { it.subscriptionId == defaultDataSubId || it.isDefaultData }
                    .forEach { info ->
                        deps.trafficTracker.resetBaseline(info.identity.stableId)
                    }

                // Final refresh after all changes to capture results of auto-reset
                deps.usageStateRepository.refresh()

                // Refresh widgets and snapshot
                deps.dataChangeNotifier.notifyDataChanged(updateWidget = true, force = true)

                // Schedule periodic monitoring if permissions are granted
                if (deps.permissionManager.hasPhoneStatePermission()) {
                    deps.workScheduler.schedulePeriodicUsageCheck()
                }

                return Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
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

    private suspend fun checkAndPerformMigrations(): Result? {
            val migrationV1Completed =
                deps.migrationFlagsRepository.isMigrationV1Completed().firstWithTimeout()
                    ?: throw IOException("DataStore timeout reading migrationV1 flag")
            val migrationStabilityCompleted =
                deps.migrationFlagsRepository.isMigrationIdentityStabilityCompleted().firstWithTimeout()
                    ?: throw IOException("DataStore timeout reading migrationStability flag")

        if (migrationV1Completed && migrationStabilityCompleted) return null

            val hasPermission = deps.permissionManager.hasPhoneStatePermission()

        return if (!hasPermission) {
                handleMigrationWithoutPermission(migrationV1Completed)
            } else {
                handleMigrationWithPermission(migrationV1Completed)
            }
        }

    private suspend fun handleMigrationWithoutPermission(v1Completed: Boolean): Result? {
        val hasLegacyData =
            try {
                deps.migrationService.hasLegacyData()
            } catch (e: IOException) {
                AppLogger.w("InitialSetupWorker: Failed to check legacy data (IO), retrying", e)
                return Result.retry()
            }

        if (hasLegacyData) {
                AppLogger.d("Migration deferred: permission missing but legacy data exists")
            } else {
                if (!v1Completed) {
                    deps.migrationFlagsRepository.setMigrationV1Completed(true)
                    deps.migrationFlagsRepository.setMigrationV1Skipped(MigrationSkipReason.NO_LEGACY_DATA)
                    AppLogger.d("Migration V1 skipped: no legacy data found")
                }
                deps.migrationFlagsRepository.setMigrationIdentityStabilityCompleted(true)
                deps.migrationService.markSchemaCurrent()
            }
        return null
        }

    private suspend fun handleMigrationWithPermission(v1Completed: Boolean): Result? {
            val activeSims = getActiveSimsWithRetry()
            val activeIdentities = activeSims.map { it.identity.stableId }.toSet()

        val hasLegacyData =
            try {
                deps.migrationService.hasLegacyData(activeIdentities)
            } catch (e: IOException) {
                AppLogger.w("InitialSetupWorker: Failed to check legacy data (IO), retrying", e)
                return Result.retry()
            }

            if (activeSims.isEmpty()) {
                if (hasLegacyData) {
                    AppLogger.d("Migration deferred: no active SIMs found but legacy data exists")
                } else {
                    if (!v1Completed) {
                        deps.migrationFlagsRepository.setMigrationV1Completed(true)
                        deps.migrationFlagsRepository.setMigrationV1Skipped(MigrationSkipReason.NO_LEGACY_DATA)
                        AppLogger.d("Migration V1 skipped: no active SIMs and no legacy data")
                    }
                    deps.migrationFlagsRepository.setMigrationIdentityStabilityCompleted(true)
                    deps.migrationService.markSchemaCurrent()
                }
            } else {
                activeSims.forEach { info ->
                    deps.migrationService.migrateLegacyData(info.identity)
                }
                deps.migrationFlagsRepository.setMigrationV1Completed(true)
                deps.migrationFlagsRepository.setMigrationIdentityStabilityCompleted(true)
                deps.migrationService.markSchemaCurrent()
                AppLogger.d("Identity migrations completed in InitialSetupWorker")
            }
        return null
        }

        private suspend fun getActiveSimsWithRetry(): List<ActiveSimInfo> {
            val firstAttempt = deps.simIdentityRepository.observeActiveSims().firstWithTimeout()
            if (firstAttempt != null) return firstAttempt

            AppLogger.w("InitialSetupWorker: observeActiveSims timed out, retrying with 15s...")

            val secondAttempt = deps.simIdentityRepository.observeActiveSims().firstWithTimeout(15000L)
            return secondAttempt ?: throw IOException("Timeout observing active SIMs")
        }
    }
