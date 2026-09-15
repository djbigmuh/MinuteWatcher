package com.example.minutewatcher.core.domain

import kotlinx.coroutines.flow.Flow
import java.io.IOException

/**
 * Repository for call session data.
 *
 * All methods are safe to call from any coroutine context.
 * Implementation must comply with AOSP DataStore best practices.
 */
@Suppress("TooManyFunctions")
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
     * Returns a list of subscription IDs that currently have active call sessions.
     *
     * @return List of subscription IDs, or null if the operation timed out or failed.
     * @throws IOException if storage access fails fundamentally.
     */
    suspend fun getActiveCallSubIds(): List<Int>?
}
