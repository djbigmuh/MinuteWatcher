package com.example.minutewatcher.core.domain

import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.MigrationSkipReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Repository for application settings.
 *
 * All methods are safe to call from any coroutine context.
 * Implementation must comply with AOSP DataStore best practices, ensuring atomic operations.
 */
interface AppSettingsRepository {
    /**
     * Returns a flow of application settings.
     */
    fun getAppSettings(): Flow<AppSettings>

    /**
     * Returns the current application settings once.
     */
    suspend fun getAppSettingsOnce(): AppSettings = getAppSettings().first()

    /**
     * Observes application settings as a StateFlow.
     */
    fun observeAppSettings(): StateFlow<AppSettings>

    /**
     * Updates application settings atomically.
     *
     * @param transform Transformation function for settings.
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun updateAppSettings(transform: (AppSettings) -> AppSettings)
}

/**
 * Repository for call session data.
 *
 * All methods are safe to call from any coroutine context.
 * Implementation must comply with AOSP DataStore best practices.
 */
interface CallSessionRepository {
    /**
     * Returns a flow of the last data subscription ID.
     */
    fun getLastDataSubId(): Flow<Int>

    /**
     * Sets the last data subscription ID.
     *
     * @throws IOException if storage access fails.
     */
    suspend fun setLastDataSubId(subId: Int)

    /**
     * Returns a flow of the call start time for the given subscription.
     */
    fun getCallStartTime(subId: Int): Flow<Long>

    /**
     * Sets the call start time for the given subscription.
     *
     * @throws IOException if storage access fails.
     */
    suspend fun setCallStartTime(
        subId: Int,
        time: Long,
    )

    /**
     * Returns a flow of the boot time for the given subscription.
     */
    fun getBootTime(subId: Int): Flow<Long>

    /**
     * Sets the boot time for the given subscription.
     *
     * @throws IOException if storage access fails.
     */
    suspend fun setBootTime(
        subId: Int,
        time: Long,
    )

    /**
     * Returns a flow of the uptime for the given subscription.
     */
    fun getUptime(subId: Int): Flow<Long>

    /**
     * Sets the uptime for the given subscription.
     *
     * @throws IOException if storage access fails.
     */
    suspend fun setUptime(
        subId: Int,
        time: Long,
    )

    /**
     * Returns a flow of the call identity for the given subscription.
     */
    fun getCallIdentity(subId: Int): Flow<String?>

    /**
     * Sets the call identity for the given subscription.
     *
     * @throws IOException if storage access fails.
     */
    suspend fun setCallIdentity(
        subId: Int,
        identity: String?,
    )

    /**
     * Clears all call session data.
     *
     * @throws IOException if storage access fails.
     */
    suspend fun clearAllCallSessions()

    /**
     * Returns a list of active call subscription IDs.
     *
     * @throws IOException if storage access fails.
     */
    suspend fun getActiveCallSubIds(): List<Int>
}

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
}
