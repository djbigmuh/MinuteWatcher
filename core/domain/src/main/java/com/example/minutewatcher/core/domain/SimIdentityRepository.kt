package com.example.minutewatcher.core.domain

import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.SimIdentity
import kotlinx.coroutines.flow.Flow

interface SimIdentityRepository {
    suspend fun getIdentity(slot: Int): String

    suspend fun getSimIdentity(slot: Int): SimIdentity

    fun getActiveIdentities(): Flow<List<String>>

    fun observeActiveSims(): Flow<List<ActiveSimInfo>>

    fun observeIdentities(): Flow<List<String>>

    fun getSimOperator(identity: String): Flow<String>

    fun getSimCode(identity: String): Flow<String>

    suspend fun setSimOperator(
        identity: String,
        name: String,
    )

    fun getCustomSimOperator(identity: String): Flow<String?>

    suspend fun setCustomSimOperator(
        identity: String,
        name: String?,
    )

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
