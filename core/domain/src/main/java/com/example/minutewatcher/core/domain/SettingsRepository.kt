package com.example.minutewatcher.core.domain

import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.AppTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface SettingsRepository {

    fun getAppSettings(): Flow<AppSettings>
    fun observeAppSettings(): StateFlow<AppSettings>

    fun isMigrationV1Completed(): Flow<Boolean>
    suspend fun setMigrationV1Completed(completed: Boolean)


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

    // Tracking helpers
    fun getLastDataSubId(): Flow<Int>
    suspend fun setLastDataSubId(subId: Int)
    
    fun getCallStartTime(): Flow<Long>
    suspend fun setCallStartTime(time: Long)

    fun getBootTime(): Flow<Long>
    suspend fun setBootTime(time: Long)

    fun getLastCallSubId(): Flow<Int>
    suspend fun setLastCallSubId(subId: Int)

    fun getSimIdentitySeed(): Flow<String>
    suspend fun setSimIdentitySeed(seed: String)

    suspend fun updateSettings(block: suspend (Any) -> Unit)
}
