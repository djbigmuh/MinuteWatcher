package com.example.minutewatcher.core.domain

import com.example.minutewatcher.core.model.MigrationSkipReason
import kotlinx.coroutines.flow.Flow
import java.io.IOException

/**
 * Repository for migration flags.
 *
 * All methods are safe to call from any coroutine context.
 * Implementation must comply with AOSP DataStore best practices.
 */
interface MigrationFlagsRepository {
    /**
     * Returns a flow indicating if V1 migration is completed.
     */
    fun isMigrationV1Completed(): Flow<Boolean>

    /**
     * Sets the V1 migration completion state.
     *
     * @throws IOException if storage access fails.
     */
    suspend fun setMigrationV1Completed(completed: Boolean)

    /**
     * Returns a flow indicating if identity stability migration is completed.
     */
    fun isMigrationIdentityStabilityCompleted(): Flow<Boolean>

    /**
     * Sets the identity stability migration completion state.
     *
     * @throws IOException if storage access fails.
     */
    suspend fun setMigrationIdentityStabilityCompleted(completed: Boolean)

    /**
     * Returns a flow of the V1 migration skip reason.
     */
    fun getMigrationV1SkippedReason(): Flow<String?>

    /**
     * Sets the V1 migration skip reason.
     *
     * @throws IOException if storage access fails.
     */
    suspend fun setMigrationV1Skipped(reason: MigrationSkipReason)

    /**
     * Returns a flow indicating if initial setup is completed.
     */
    fun isInitialSetupCompleted(): Flow<Boolean>

    /**
     * Sets the initial setup completion state.
     *
     * @throws IOException if storage access fails.
     */
    suspend fun setInitialSetupCompleted(completed: Boolean)
}
