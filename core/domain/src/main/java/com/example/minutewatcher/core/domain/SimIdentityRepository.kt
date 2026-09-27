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
     * @throws InvalidSlotException if the slot index is invalid.
     * @throws SimNotFoundException if no SIM is in the slot.
     * @throws SeedUnavailableException if identity seed is unavailable within timeout (storage failure).
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun getIdentity(slot: Int): String

    /**
     * Returns the full [SimIdentity] for the given slot.
     *
     * @throws InvalidSlotException if the slot index is invalid.
     * @throws SimNotFoundException if no SIM is in the slot.
     * @throws SeedUnavailableException if identity seed is unavailable within timeout (storage failure).
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
     * Sets the operator name for the given identity.
     *
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun setSimOperator(
        identity: String,
        name: String,
    )
}

/**
 * Exception thrown when there is no active SIM subscription in the specified slot.
 */
class SimNotFoundException(
    val slot: Int,
) : IllegalStateException("No active SIM in slot $slot")

/**
 * Exception thrown when an invalid SIM slot index is passed.
 */
class InvalidSlotException(
    val slot: Int,
) : IllegalArgumentException("Invalid SIM slot index: $slot")

/**
 * Exception thrown when identity seed is unavailable (storage failure).
 */
class SeedUnavailableException(
    val slot: Int? = null,
    message: String = "SIM identity seed unavailable",
) : IllegalStateException(message)
