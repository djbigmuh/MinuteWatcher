package com.example.minutewatcher.core.domain

import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.MigrationSkipReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface AppSettingsRepository {
    fun getAppSettings(): Flow<AppSettings>

    fun observeAppSettings(): StateFlow<AppSettings>

    suspend fun updateAppSettings(transform: (AppSettings) -> AppSettings)
}

interface CallSessionRepository {
    fun getLastDataSubId(): Flow<Int>

    suspend fun setLastDataSubId(subId: Int)

    fun getCallStartTime(subId: Int): Flow<Long>

    suspend fun setCallStartTime(
        subId: Int,
        time: Long,
    )

    fun getBootTime(subId: Int): Flow<Long>

    suspend fun setBootTime(
        subId: Int,
        time: Long,
    )

    fun getUptime(subId: Int): Flow<Long>

    suspend fun setUptime(
        subId: Int,
        time: Long,
    )

    fun getCallIdentity(subId: Int): Flow<String?>

    suspend fun setCallIdentity(
        subId: Int,
        identity: String?,
    )

    suspend fun clearAllCallSessions()

    suspend fun getActiveCallSubIds(): List<Int>
}

interface MigrationFlagsRepository {
    fun isMigrationV1Completed(): Flow<Boolean>

    suspend fun setMigrationV1Completed(completed: Boolean)

    fun isMigrationIdentityStabilityCompleted(): Flow<Boolean>

    suspend fun setMigrationIdentityStabilityCompleted(completed: Boolean)

    fun getMigrationV1SkippedReason(): Flow<String?>

    suspend fun setMigrationV1Skipped(reason: MigrationSkipReason)
}
