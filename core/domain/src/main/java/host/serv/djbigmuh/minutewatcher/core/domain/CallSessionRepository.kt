package host.serv.djbigmuh.minutewatcher.core.domain

import kotlinx.coroutines.flow.Flow
import java.io.IOException

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
     * Atomically saves all call session data for the given subscription.
     *
     * @param subId Subscription ID.
     * @param startTime Local start time of the call.
     * @param elapsedRealtime Device elapsedRealtime when the call started.
     * @param uptime Device uptime when the call started.
     * @param identity Stable SIM identity. If null, the identity key is removed.
     * @throws IOException if storage access fails.
     */
    suspend fun saveCallSession(
        subId: Int,
        startTime: Long,
        elapsedRealtime: Long,
        uptime: Long,
        identity: String?,
    )

    /**
     * Atomically clears all call session keys for the given subscription.
     *
     * @throws IOException if storage access fails.
     */
    suspend fun clearCallSession(subId: Int)

    /**
     * Returns a flow of the call start time for the given subscription.
     */
    fun getCallStartTime(subId: Int): Flow<Long>

    /**
     * Returns a flow of the elapsed realtime for the given subscription.
     */
    fun getElapsedRealtime(subId: Int): Flow<Long>

    /**
     * Returns a flow of the uptime for the given subscription.
     */
    fun getUptime(subId: Int): Flow<Long>

    /**
     * Returns a flow of the call identity for the given subscription.
     */
    fun getCallIdentity(subId: Int): Flow<String?>

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
