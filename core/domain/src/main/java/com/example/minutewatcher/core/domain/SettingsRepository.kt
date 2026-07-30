package com.example.minutewatcher.core.domain

import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.AppTheme
import com.example.minutewatcher.core.model.MigrationSkipReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface AppSettingsRepository {
    fun getAppSettings(): Flow<AppSettings>

    fun observeAppSettings(): StateFlow<AppSettings>

    fun getAppTheme(): Flow<AppTheme>

    suspend fun setAppTheme(theme: AppTheme)

    fun getAppLanguage(): Flow<String?>

    suspend fun setAppLanguage(language: String?)

    // Widget general settings
    fun getWidgetLayoutMode(): Flow<Int>

    suspend fun setWidgetLayoutMode(mode: Int)

    fun getWidgetSimArrangement(): Flow<Int>

    suspend fun setWidgetSimArrangement(arrangement: Int)

    fun getWidgetTransparency(): Flow<Int>

    suspend fun setWidgetTransparency(transparency: Int)

    fun getWidgetFontSize(): Flow<Int>

    suspend fun setWidgetFontSize(size: Int)

    fun getWidgetBackgroundColor(): Flow<Int>

    suspend fun setWidgetBackgroundColor(color: Int)

    // Notification flags
    fun getNotifyLowMinutes(): Flow<Boolean>

    suspend fun setNotifyLowMinutes(enabled: Boolean)

    fun getNotifyLowTraffic(): Flow<Boolean>

    suspend fun setNotifyLowTraffic(enabled: Boolean)

    fun getCallTrackingEnabled(): Flow<Boolean>

    suspend fun setCallTrackingEnabled(enabled: Boolean)

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
