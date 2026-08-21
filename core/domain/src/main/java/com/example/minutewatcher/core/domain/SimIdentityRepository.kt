package com.example.minutewatcher.core.domain

import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.SimIdentity
import kotlinx.coroutines.flow.Flow
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Repository for SIM identity and operator information.
 *
 * All methods are safe to call from any coroutine context.
 * Implementation must comply with AOSP DataStore best practices.
 * Operations are typically bounded by SHORT_TIMEOUT_MS.
 */
interface SimIdentityRepository {
    /**
     * Returns the identity string for the given slot.
     *
     * @throws SimNotFoundException if no SIM is in the slot.
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun getIdentity(slot: Int): String

    /**
     * Returns the full [SimIdentity] for the given slot.
     *
     * @throws SimNotFoundException if no SIM is in the slot.
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun getSimIdentity(slot: Int): SimIdentity

    /**
     * Returns a flow of active SIM identity strings.
     */
    fun getActiveIdentities(): Flow<List<String>>

    /**
     * Observes information about all active SIM cards.
     */
    fun observeActiveSims(): Flow<List<ActiveSimInfo>>

    /**
     * Observes all known SIM identity strings.
     */
    fun observeIdentities(): Flow<List<String>>

    /**
     * Returns a flow of the operator name for the given identity.
     */
    fun getSimOperator(identity: String): Flow<String>

    /**
     * Returns a flow of the SIM code for the given identity.
     */
    fun getSimCode(identity: String): Flow<String>

    /**
     * Sets the operator name for the given identity.
     *
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun setSimOperator(
        identity: String,
        name: String,
    )

    /**
     * Returns a flow of the custom operator name for the given identity.
     */
    fun getCustomSimOperator(identity: String): Flow<String?>

    /**
     * Sets the custom operator name for the given identity.
     *
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun setCustomSimOperator(
        identity: String,
        name: String?,
    )

    /**
     * Sets the SIM code for the given identity.
     *
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun setSimCode(
        identity: String,
        code: String,
    )
}

/**
 * Исключение, выбрасываемое при отсутствии активной подписки SIM в указанном слоте.
 */
class SimNotFoundException(
    val slot: Int,
) : IllegalStateException("No active SIM in slot $slot")

/**
 * Исключение, выбрасываемое при недоступности identity seed (сбой хранилища).
 */
class SeedUnavailableException(
    val slot: Int,
) : IllegalStateException("Identity seed unavailable for slot $slot")
