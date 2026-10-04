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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
        /**
         * Orchestrates initial setup and migrations after boot or app update.
         *
         * **Reliability criteria:**
         * The worker always completes within [WORKER_TIMEOUT_MS] to avoid blocking
         * the unique setup work chain. Exceeding this budget is treated as temporary
         * storage degradation and triggers [Result.retry].
         *
         * @throws CancellationException if the work is cancelled by WorkManager.
         */
        override suspend fun doWork(): Result =
            withTimeoutOrNull(WORKER_TIMEOUT_MS.milliseconds) {
                executeWork()
            } ?: run {
                AppLogger.e("InitialSetupWorker: timed out after ${WORKER_TIMEOUT_MS}ms")
                Result.retry()
            }

        private suspend fun executeWork(): Result =
            try {
                performSetupSteps()
                Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                AppLogger.w("InitialSetupWorker: Network or storage error, retrying. ${e.message}")
                Result.retry()
            } catch (e: Exception) {
                AppLogger.e("InitialSetupWorker failed due to unexpected error", e)
                Result.failure()
            }

        private enum class MigrationDecision {
            PROCEED,
            RETRY,
            FAIL,
        }

        private suspend fun performSetupSteps() {
            withTimeoutOrNull(REFRESH_OPERATOR_TIMEOUT_MS.milliseconds) {
                deps.usageUseCase.refreshOperatorInfo()
            } ?: AppLogger.w("InitialSetupWorker: refreshOperatorInfo timed out after ${REFRESH_OPERATOR_TIMEOUT_MS}ms")

            if (inputData.getBoolean(EXTRA_TRIGGERED_BY_BOOT, false)) {
                withTimeoutOrNull(Constants.Flow.LONG_TIMEOUT_MS.milliseconds) {
                    deps.callTracker.onBootAwaited()
                } ?: AppLogger.e("InitialSetupWorker: boot session cleanup timed out")
            }

            when (checkAndPerformMigrations()) {
                MigrationDecision.RETRY -> throw IOException("Migration retry")
                MigrationDecision.FAIL -> throw IllegalStateException("Migration failed")
                MigrationDecision.PROCEED -> {
                    // Continue setup steps
                }
            }

            performCleanupSafe {
                if (!deps.autoResetUseCase.performAutoReset(shouldNotify = false)) {
                    AppLogger.w("InitialSetupWorker: Auto-reset partially failed")
                }
            }
            performCleanupSafe { deps.trafficTracker.updateTrafficUsage() }

            deps.usageStateRepository.refresh()
            deps.dataChangeNotifier.notifyDataChanged(updateWidget = true, force = true)

            if (deps.permissionManager.hasPhoneStatePermission()) {
                deps.workScheduler.schedulePeriodicUsageCheck()
            }
            deps.migrationFlagsRepository.setInitialSetupCompleted(true)
        }

        private suspend fun performCleanupSafe(block: suspend () -> Unit) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("InitialSetupWorker: cleanup step failed", e)
            }
        }

        private suspend fun checkAndPerformMigrations(): MigrationDecision {
            val v1Completed = fetchFlag("migrationV1") { deps.migrationFlagsRepository.isMigrationV1Completed() }
            val stabilityCompleted =
                fetchFlag("stability") { deps.migrationFlagsRepository.isMigrationIdentityStabilityCompleted() }

            if (v1Completed && stabilityCompleted) return MigrationDecision.PROCEED

            return if (!deps.permissionManager.hasPhoneStatePermission()) {
                handleMigrationWithoutPermission(v1Completed)
            } else {
                handleMigrationWithPermission(v1Completed)
            }
        }

        private suspend fun fetchFlag(
            name: String,
            provider: () -> Flow<Boolean>,
        ): Boolean =
            try {
                provider().firstWithTimeout()
            } catch (e: IOException) {
                AppLogger.e("InitialSetupWorker: Critical error reading $name flag", e)
                throw IOException("Failed to read $name flag", e)
            } ?: throw IOException("DataStore timeout reading $name flag")

        private suspend fun handleMigrationWithoutPermission(v1Completed: Boolean): MigrationDecision {
            val hasLegacyData = deps.migrationService.hasLegacyData()
            if (hasLegacyData == null) {
                return if (runAttemptCount < MAX_MIGRATION_ATTEMPTS) {
                    MigrationDecision.RETRY
                } else {
                    markMigrationSkippedPermanently()
                }
            }

            if (!hasLegacyData) {
                if (!v1Completed) {
                    deps.migrationFlagsRepository.setMigrationV1Completed(completed = true)
                    deps.migrationFlagsRepository.setMigrationV1Skipped(MigrationSkipReason.NO_LEGACY_DATA)
                }
                deps.migrationFlagsRepository.setMigrationIdentityStabilityCompleted(completed = true)
                deps.migrationService.markSchemaCurrent()
            }
            return MigrationDecision.PROCEED
        }

        private suspend fun handleMigrationWithPermission(v1Completed: Boolean): MigrationDecision {
            val activeSims = getActiveSimsWithRetry()

            val activeIdentities = activeSims.mapTo(mutableSetOf()) { it.identity.stableId }
            val hasLegacyData = deps.migrationService.hasLegacyData(activeIdentities)

            return when {
                hasLegacyData == null -> {
                    if (runAttemptCount < MAX_MIGRATION_ATTEMPTS) {
                        MigrationDecision.RETRY
                    } else {
                        markMigrationSkippedPermanently()
                    }
                }

                activeSims.isEmpty() -> {
                    if (!hasLegacyData) {
                        if (!v1Completed) {
                            deps.migrationFlagsRepository.setMigrationV1Completed(completed = true)
                            deps.migrationFlagsRepository.setMigrationV1Skipped(MigrationSkipReason.NO_LEGACY_DATA)
                        }
                        deps.migrationFlagsRepository.setMigrationIdentityStabilityCompleted(completed = true)
                        deps.migrationService.markSchemaCurrent()
                    }
                    MigrationDecision.PROCEED
                }

                else -> performActualMigration(activeSims)
            }
        }

        private suspend fun markMigrationSkippedPermanently(): MigrationDecision {
            AppLogger.e(
                "InitialSetupWorker: legacy data availability could not be determined " +
                    "after $MAX_MIGRATION_ATTEMPTS attempts, skipping migration",
            )
            deps.migrationFlagsRepository.setMigrationV1Completed(completed = true)
            deps.migrationFlagsRepository.setMigrationV1Skipped(MigrationSkipReason.LEGACY_DATA_CHECK_FAILED)
            deps.migrationFlagsRepository.setMigrationIdentityStabilityCompleted(completed = true)
            return MigrationDecision.PROCEED
        }

        private suspend fun performActualMigration(activeSims: List<ActiveSimInfo>): MigrationDecision =
            try {
                var totalFailures = 0
                activeSims.forEach { info ->
                    val outcome = deps.migrationService.migrateLegacyData(info.identity)
                    totalFailures += outcome.failures
                }

                if (totalFailures > 0) {
                    if (runAttemptCount < MAX_MIGRATION_ATTEMPTS) return MigrationDecision.RETRY
                    AppLogger.e("InitialSetupWorker: Max migration attempts reached with $totalFailures failures.")
                }

                deps.migrationFlagsRepository.setMigrationV1Completed(completed = true)
                deps.migrationFlagsRepository.setMigrationIdentityStabilityCompleted(completed = true)
                deps.migrationService.markSchemaCurrent()
                MigrationDecision.PROCEED
            } catch (e: IOException) {
                AppLogger.w("InitialSetupWorker: Actual migration failed due to IO error, retrying", e)
                MigrationDecision.RETRY
            }

        /**
         * Fetches active SIM cards with a bounded wait for the first non-empty list.
         *
         * On a cold start, the discovery process may take some time. This method waits
         * up to [Constants.Flow.INITIAL_SETUP_RETRY_TIMEOUT_MS] for at least one SIM
         * to be discovered. If the timeout expires, it returns the current (potentially empty)
         * list, which is correct for devices without SIM cards.
         *
         * @return List of active SIM cards.
         */
        private suspend fun getActiveSimsWithRetry(): List<ActiveSimInfo> {
            val flow = deps.simIdentityRepository.observeActiveSims()
            return withTimeoutOrNull(Constants.Flow.INITIAL_SETUP_RETRY_TIMEOUT_MS.milliseconds) {
                flow.first { it.isNotEmpty() }
            } ?: flow.firstWithTimeout(Constants.Flow.SHORT_TIMEOUT_MS) ?: emptyList()
        }

        companion object {
            const val EXTRA_TRIGGERED_BY_BOOT = "triggered_by_boot"
            private const val MAX_MIGRATION_ATTEMPTS = 3
            private const val WORKER_TIMEOUT_MS = 120_000L

            /**
             * Maximum time allocated for refreshing operator info during initial setup.
             *
             * Updating operator names is not a critical step and must not consume the overall
             * worker budget during storage degradation.
             */
            private const val REFRESH_OPERATOR_TIMEOUT_MS = 30_000L
        }
    }
