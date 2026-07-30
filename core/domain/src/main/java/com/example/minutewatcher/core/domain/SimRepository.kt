package com.example.minutewatcher.core.domain

import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.SimIdentity
import com.example.minutewatcher.core.model.SimSettingsState
import com.example.minutewatcher.core.model.SimUsage
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

    suspend fun setSimCode(
        identity: String,
        code: String,
    )
}

interface SimUsageRepository {
    fun getSimUsage(identity: String): Flow<SimUsage>

    fun getMinutesUsed(identity: String): Flow<Int>

    suspend fun setMinutesUsed(
        identity: String,
        value: Int,
    )

    suspend fun incrementMinutesUsed(
        identity: String,
        delta: Int,
    )

    fun getTrafficUsed(identity: String): Flow<Float>

    suspend fun setTrafficUsed(
        identity: String,
        value: Float,
    )

    suspend fun incrementTrafficUsed(
        identity: String,
        deltaMb: Float,
    )

    fun getManualAdjustment(identity: String): Flow<Int>

    suspend fun setManualAdjustment(
        identity: String,
        value: Int,
    )

    fun getTrafficAdjustment(identity: String): Flow<Float>

    suspend fun setTrafficAdjustment(
        identity: String,
        value: Float,
    )

    suspend fun updateSimStats(
        identity: String,
        minutesUsed: Int,
        trafficUsed: Float,
        manualAdjustment: Int,
    )

    suspend fun resetSimUsage(identity: String)

    suspend fun resetSimUsageIfDue(
        identity: String,
        autoResetDay: Int,
        nowMillis: Long,
    ): Boolean

    fun getLastResetDate(identity: String): Flow<Long>

    suspend fun setLastResetDate(
        identity: String,
        value: Long,
    )

    fun getLastTotalBytes(identity: String): Flow<Long>

    suspend fun setLastTotalBytes(
        identity: String,
        bytes: Long,
    )
}

interface SimLimitsRepository {
    fun getMonthlyLimit(identity: String): Flow<Int>

    fun getTrafficLimit(identity: String): Flow<Int>

    suspend fun updateSimLimits(
        identity: String,
        minutesLimit: Int,
        trafficLimitMb: Int,
        rolloverEnabled: Boolean = false,
    )

    fun getAutoReset(identity: String): Flow<Boolean>

    fun getAutoResetDay(identity: String): Flow<Int>

    suspend fun setAutoReset(
        identity: String,
        day: Int,
        enabled: Boolean,
    )
}

interface SimWidgetVisibilityRepository {
    fun getSimSettings(identity: String): Flow<SimSettingsState>

    suspend fun setSimSettingsBatch(settingsMap: Map<String, SimSettingsState>)

    fun getShowSim(identity: String): Flow<Boolean>

    suspend fun setShowSim(
        identity: String,
        show: Boolean,
    )

    fun getShowSimIcon(identity: String): Flow<Boolean>

    suspend fun setShowSimIcon(
        identity: String,
        show: Boolean,
    )

    fun getShowSimName(identity: String): Flow<Boolean>

    suspend fun setShowSimName(
        identity: String,
        show: Boolean,
    )

    fun getShowMinutes(identity: String): Flow<Boolean>

    suspend fun setShowMinutes(
        identity: String,
        show: Boolean,
    )

    fun getShowMinutesLimit(identity: String): Flow<Boolean>

    suspend fun setShowMinutesLimit(
        identity: String,
        show: Boolean,
    )

    fun getShowTraffic(identity: String): Flow<Boolean>

    suspend fun setShowTraffic(
        identity: String,
        show: Boolean,
    )

    fun getShowTrafficLimit(identity: String): Flow<Boolean>

    suspend fun setShowTrafficLimit(
        identity: String,
        show: Boolean,
    )
}

interface SimNotificationFlagsRepository {
    fun getNotifiedLowMinutes(identity: String): Flow<Boolean>

    suspend fun setNotifiedLowMinutes(
        identity: String,
        notified: Boolean,
    )

    fun getNotifiedLowTraffic(identity: String): Flow<Boolean>

    suspend fun setNotifiedLowTraffic(
        identity: String,
        notified: Boolean,
    )

    fun getNotifiedExhaustedMinutes(identity: String): Flow<Boolean>

    suspend fun setNotifiedExhaustedMinutes(
        identity: String,
        notified: Boolean,
    )

    fun getNotifiedExhaustedTraffic(identity: String): Flow<Boolean>

    suspend fun setNotifiedExhaustedTraffic(
        identity: String,
        notified: Boolean,
    )
}
