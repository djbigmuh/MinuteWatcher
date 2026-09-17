package com.example.minutewatcher.core.data

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.MigrationSkipReason
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

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
                withTimeoutOrNull(Constants.Flow.LONG_TIMEOUT_MS.milliseconds) {
                    deps.callTracker.onBootAwaited()
                } ?: AppLogger.e("InitialSetupWorker: boot session cleanup timed out")

                checkAndPerformMigrations()?.let { return it }

                // Perform auto-reset immediately after boot/migration. Skip internal notify as we do it at the end.
                try {
                    val allReset = deps.autoResetUseCase.performAutoReset(shouldNotify = false)
                    if (!allReset) {
                        AppLogger.w("InitialSetupWorker: Auto-reset partially failed, but continuing.")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.w("InitialSetupWorker: Auto-reset failed with error, continuing.", e)
                }

                // Update traffic usage after reboot to capture any traffic since last update.
                // updateTrafficUsage() correctly handles baseline alignment and SIM changes.
                try {
                    deps.trafficTracker.updateTrafficUsage()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.w("InitialSetupWorker: Traffic update failed, continuing.", e)
                }

                // Final refresh after all changes to capture results of auto-reset
                deps.usageStateRepository.refresh()

                // Refresh widgets and snapshot
                deps.dataChangeNotifier.notifyDataChanged(updateWidget = true, force = true)

                // Schedule periodic monitoring if permissions are granted
                if (deps.permissionManager.hasPhoneStatePermission()) {
                    deps.workScheduler.schedulePeriodicUsageCheck()
                }

                deps.migrationFlagsRepository.setInitialSetupCompleted(true)

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
            val migrationV1Completed = fetchMigrationV1Flag()
            val migrationStabilityCompleted = fetchMigrationStabilityFlag()

            if (migrationV1Completed && migrationStabilityCompleted) {
                return null
            }

            val hasPermission = deps.permissionManager.hasPhoneStatePermission()

            return if (!hasPermission) {
                handleMigrationWithoutPermission(migrationV1Completed)
            } else {
                handleMigrationWithPermission(migrationV1Completed)
            }
        }

        private suspend fun fetchMigrationV1Flag(): Boolean =
            try {
                deps.migrationFlagsRepository.isMigrationV1Completed().firstWithTimeout()
            } catch (e: IOException) {
                AppLogger.e("InitialSetupWorker: Critical error reading migrationV1 flag", e)
                throw IOException("Failed to read migrationV1 flag", e)
            } ?: throw IOException("DataStore timeout reading migrationV1 flag")

        private suspend fun fetchMigrationStabilityFlag(): Boolean =
            try {
                deps.migrationFlagsRepository.isMigrationIdentityStabilityCompleted().firstWithTimeout()
            } catch (e: IOException) {
                AppLogger.e("InitialSetupWorker: Critical error reading migrationStability flag", e)
                throw IOException("Failed to read migrationStability flag", e)
            } ?: throw IOException("DataStore timeout reading migrationStability flag")

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
                    deps.migrationFlagsRepository.setMigrationV1Completed(completed = true)
                    deps.migrationFlagsRepository.setMigrationV1Skipped(MigrationSkipReason.NO_LEGACY_DATA)
                    AppLogger.d("Migration V1 skipped: no legacy data found")
                }
                deps.migrationFlagsRepository.setMigrationIdentityStabilityCompleted(completed = true)
                deps.migrationService.markSchemaCurrent()
            }
            return null
        }

        private suspend fun handleMigrationWithPermission(v1Completed: Boolean): Result? {
            var activeSims: List<ActiveSimInfo>? = null
            try {
                activeSims = getActiveSimsWithRetry()
            } catch (e: IOException) {
                AppLogger.w("InitialSetupWorker: Failed to get active SIMs for migration, retrying", e)
            }

            var finalResult: Result? = null
            if (activeSims == null) {
                finalResult = Result.retry()
            } else {
                val activeIdentities = activeSims.mapTo(mutableSetOf()) { it.identity.stableId }
                var hasLegacyData: Boolean? = null
                try {
                    hasLegacyData = deps.migrationService.hasLegacyData(activeIdentities)
                } catch (e: IOException) {
                    AppLogger.w("InitialSetupWorker: Failed to check legacy data (IO), retrying", e)
                }

                if (hasLegacyData == null) {
                    finalResult = Result.retry()
                } else if (activeSims.isEmpty()) {
                    handleEmptySimsMigration(v1Completed, hasLegacyData)
                } else {
                    finalResult = performActualMigration(activeSims)
                }
            }
            return finalResult
        }

        private suspend fun handleEmptySimsMigration(
            v1Completed: Boolean,
            hasLegacyData: Boolean,
        ) {
            if (hasLegacyData) {
                AppLogger.d("Migration deferred: no active SIMs found but legacy data exists")
            } else {
                if (!v1Completed) {
                    deps.migrationFlagsRepository.setMigrationV1Completed(completed = true)
                    deps.migrationFlagsRepository.setMigrationV1Skipped(MigrationSkipReason.NO_LEGACY_DATA)
                    AppLogger.d("Migration V1 skipped: no active SIMs and no legacy data")
                }
                deps.migrationFlagsRepository.setMigrationIdentityStabilityCompleted(completed = true)
                deps.migrationService.markSchemaCurrent()
            }
        }

        private suspend fun performActualMigration(activeSims: List<ActiveSimInfo>): Result? =
            try {
                var totalFailures = 0
                var totalConflicts = 0
                activeSims.forEach { info ->
                    val outcome = deps.migrationService.migrateLegacyData(info.identity)
                    totalFailures += outcome.failures
                    totalConflicts += outcome.conflicts
                }

                if (totalConflicts > 0) {
                    AppLogger.w(
                        "InitialSetupWorker: Migration resolved $totalConflicts conflicts (legacy values backed up).",
                    )
                }

                if (totalFailures > 0) {
                    AppLogger.e("InitialSetupWorker: Migration finished with $totalFailures failures.")
                    if (runAttemptCount < MAX_MIGRATION_ATTEMPTS) {
                        AppLogger.w("InitialSetupWorker: Retrying migration (attempt $runAttemptCount)")
                        return Result.retry()
                    } else {
                        AppLogger.e(
                            "InitialSetupWorker: Max migration attempts reached. " +
                                "Marking as completed despite failures.",
                        )
                    }
                }

                deps.migrationFlagsRepository.setMigrationV1Completed(completed = true)
                deps.migrationFlagsRepository.setMigrationIdentityStabilityCompleted(completed = true)
                deps.migrationService.markSchemaCurrent()
                AppLogger.d("Identity migrations completed in InitialSetupWorker")
                null
            } catch (e: IOException) {
                AppLogger.w("InitialSetupWorker: Migration failed due to IO error, retrying", e)
                Result.retry()
            }

        private suspend fun getActiveSimsWithRetry(): List<ActiveSimInfo> {
            deps.simIdentityRepository
                .observeActiveSims()
                .firstWithTimeout()
                ?.let { return it }

            AppLogger.w(
                "InitialSetupWorker: observeActiveSims timed out, retrying with " +
                    "${Constants.Flow.INITIAL_SETUP_RETRY_TIMEOUT_MS / Constants.Time.MILLIS_IN_SECOND}s...",
            )

            val secondAttempt =
                deps.simIdentityRepository.observeActiveSims().firstWithTimeout(
                    Constants.Flow.INITIAL_SETUP_RETRY_TIMEOUT_MS,
                )
            return secondAttempt ?: throw IOException("Timeout observing active SIMs")
        }

        private companion object {
            private const val MAX_MIGRATION_ATTEMPTS = 3
        }
    }
