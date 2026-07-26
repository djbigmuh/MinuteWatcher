package com.example.minutewatcher.core.data

import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.example.minutewatcher.core.data.di.SimDataStore
import com.example.minutewatcher.core.data.util.SimIdentityUtils
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.domain.SimInfoProvider
import com.example.minutewatcher.core.domain.SimRepository
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.IdentitySource
import com.example.minutewatcher.core.model.SimIdentity
import com.example.minutewatcher.core.model.SimUsage
import com.example.minutewatcher.core.model.util.PermissionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SimRepositoryImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val subscriptionManager: SubscriptionManager,
        private val permissionManager: PermissionManager,
        @SimDataStore private val dataStore: DataStore<Preferences>,
        private val seedRepository: SimIdentitySeedRepository,
    ) : SimRepository,
        SimInfoProvider {
        companion object {
            private fun monthlyLimitKey(identity: String) = intPreferencesKey("monthly_limit_$identity")

            private fun trafficLimitKey(identity: String) = intPreferencesKey("traffic_limit_$identity")

            private fun minutesUsedKey(identity: String) = intPreferencesKey("minutes_used_$identity")

            private fun manualAdjustmentKey(identity: String) = intPreferencesKey("manual_adjustment_$identity")

            private fun trafficAdjustmentKey(identity: String) = floatPreferencesKey("traffic_adjustment_$identity")

            private fun trafficUsedKey(identity: String) = floatPreferencesKey("traffic_used_$identity")

            private fun autoResetKey(identity: String) = booleanPreferencesKey("auto_reset_$identity")

            private fun autoResetDayKey(identity: String) = intPreferencesKey("auto_reset_day_$identity")

            private fun lastResetDateKey(identity: String) = longPreferencesKey("last_reset_date_$identity")

            private fun simOperatorKey(identity: String) = stringPreferencesKey("sim_operator_$identity")

            private fun simCodeKey(identity: String) = stringPreferencesKey("sim_code_$identity")

            private fun slotIndexKey(identity: String) = intPreferencesKey("slot_index_$identity")

            private fun subscriptionIdKey(identity: String) = intPreferencesKey("subscription_id_$identity")
        }

        override suspend fun getIdentity(slot: Int): String = getSimIdentity(slot).stableId

        private suspend fun getSeed(): String {
            var seed = seedRepository.getSeed().first()
            if (seed.isEmpty()) {
                seed =
                    java.util.UUID
                        .randomUUID()
                        .toString()
                seedRepository.setSeed(seed)
            }
            return seed
        }

        override suspend fun getSimIdentity(slot: Int): SimIdentity {
            val seed = getSeed()
            val identity =
                permissionManager.getActiveSubscriptions().find { it.simSlotIndex == slot }?.let {
                    val resolved = SimIdentityUtils.createIdentityFromInfo(it, seed)
                    saveSimIdentityInfo(resolved)
                    resolved
                }

            return identity ?: SimIdentity(
                stableId = "sim_$slot",
                subscriptionId = null,
                slotIndex = slot,
                source = IdentitySource.SLOT_FALLBACK,
            )
        }

        private suspend fun saveSimIdentityInfo(identity: SimIdentity) {
            dataStore.edit { prefs ->
                identity.slotIndex?.let { prefs[slotIndexKey(identity.stableId)] = it }
                identity.subscriptionId?.let { prefs[subscriptionIdKey(identity.stableId)] = it }
            }
        }

        override fun observeActiveSims(): Flow<List<ActiveSimInfo>> =
            seedRepository
                .getSeed()
                .flatMapLatest { seed ->
                    callbackFlow {
                        val listener =
                            object : SubscriptionManager.OnSubscriptionsChangedListener() {
                                override fun onSubscriptionsChanged() {
                                    launch {
                                        trySend(getActiveSimsInternal(seed))
                                    }
                                }
                            }

                        if (permissionManager.hasPhoneStatePermission()) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                subscriptionManager.addOnSubscriptionsChangedListener(context.mainExecutor, listener)
                            } else {
                                @Suppress("DEPRECATION")
                                subscriptionManager.addOnSubscriptionsChangedListener(listener)
                            }
                            trySend(getActiveSimsInternal(seed))
                        } else {
                            trySend(emptyList())
                        }

                        awaitClose {
                            subscriptionManager.removeOnSubscriptionsChangedListener(listener)
                        }
                    }
                }.distinctUntilChanged()

        private fun getActiveSimsInternal(seed: String): List<ActiveSimInfo> {
            val infoList = permissionManager.getActiveSubscriptions()
            if (infoList.isEmpty()) return emptyList()

            val defaultDataSubId = permissionManager.getDefaultDataSubscriptionId()
            val defaultVoiceSubId = permissionManager.getDefaultVoiceSubscriptionId()

            return infoList.map { info ->
                ActiveSimInfo(
                    identity = SimIdentityUtils.createIdentityFromInfo(info, seed),
                    slotIndex = info.simSlotIndex,
                    subscriptionId = info.subscriptionId,
                    displayName = info.displayName?.toString(),
                    carrierName = info.carrierName?.toString(),
                    mccMnc = SimIdentityUtils.getMccMnc(info),
                    isDefaultData = info.subscriptionId == defaultDataSubId,
                    isDefaultVoice = info.subscriptionId == defaultVoiceSubId,
                )
            }
        }

        override fun getActiveIdentities(): Flow<List<String>> =
            observeActiveSims().map { sims ->
                sims.map { it.identity.stableId }
            }

        private fun subscriptionChangesFlow(): Flow<Unit> =
            callbackFlow {
                trySend(Unit)
                val listener =
                    object : SubscriptionManager.OnSubscriptionsChangedListener() {
                        override fun onSubscriptionsChanged() {
                            trySend(Unit)
                        }
                    }

                if (permissionManager.hasPhoneStatePermission()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        subscriptionManager.addOnSubscriptionsChangedListener(context.mainExecutor, listener)
                    } else {
                        @Suppress("DEPRECATION")
                        subscriptionManager.addOnSubscriptionsChangedListener(listener)
                    }
                    awaitClose { subscriptionManager.removeOnSubscriptionsChangedListener(listener) }
                } else {
                    awaitClose { }
                }
            }

        @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
        override fun observeIdentities(): Flow<List<String>> =
            subscriptionChangesFlow()
                .debounce(300.milliseconds)
                .mapLatest {
                    getActiveIdentities().first()
                }.distinctUntilChanged()

        override fun getSimUsage(identity: String): Flow<SimUsage> =
            dataStore.data.map { prefs ->
                SimUsage(
                    identity = identity,
                    slotIndex = prefs[slotIndexKey(identity)] ?: 0,
                    subscriptionId = prefs[subscriptionIdKey(identity)],
                    operator = prefs[simOperatorKey(identity)] ?: "",
                    minutesUsed = prefs[minutesUsedKey(identity)] ?: 0,
                    minutesLimit = prefs[monthlyLimitKey(identity)] ?: 0,
                    trafficUsed = prefs[trafficUsedKey(identity)] ?: 0f,
                    trafficLimitMb = prefs[trafficLimitKey(identity)] ?: 0,
                    manualAdjustment = prefs[manualAdjustmentKey(identity)] ?: 0,
                    trafficAdjustment = prefs[trafficAdjustmentKey(identity)] ?: 0f,
                    autoReset = prefs[autoResetKey(identity)] ?: false,
                    autoResetDay = prefs[autoResetDayKey(identity)] ?: 1,
                    lastResetDate = prefs[lastResetDateKey(identity)] ?: 0L,
                )
            }

        private fun <T> get(
            key: Preferences.Key<T>,
            defaultValue: T,
        ): Flow<T> = dataStore.data.map { it[key] ?: defaultValue }

        override fun getSimOperator(identity: String): Flow<String> = get(simOperatorKey(identity), "")

        override suspend fun setSimOperator(
            identity: String,
            name: String,
        ) {
            dataStore.edit { it[simOperatorKey(identity)] = name }
        }

        override fun getSimCode(identity: String): Flow<String> = get(simCodeKey(identity), "")

        override suspend fun setSimCode(
            identity: String,
            code: String,
        ) {
            dataStore.edit { it[simCodeKey(identity)] = code }
        }

        override fun getMinutesUsed(identity: String): Flow<Int> = get(minutesUsedKey(identity), 0)

        override suspend fun setMinutesUsed(
            identity: String,
            value: Int,
        ) {
            dataStore.edit { it[minutesUsedKey(identity)] = value }
        }

        override fun getMonthlyLimit(identity: String): Flow<Int> = get(monthlyLimitKey(identity), 0)

        override fun getManualAdjustment(identity: String): Flow<Int> = get(manualAdjustmentKey(identity), 0)

        override suspend fun setManualAdjustment(
            identity: String,
            value: Int,
        ) {
            dataStore.edit { it[manualAdjustmentKey(identity)] = value }
        }

        override fun getTrafficUsed(identity: String): Flow<Float> = get(trafficUsedKey(identity), 0f)

        override suspend fun setTrafficUsed(
            identity: String,
            value: Float,
        ) {
            dataStore.edit { it[trafficUsedKey(identity)] = value }
        }

        override fun getTrafficLimit(identity: String): Flow<Int> = get(trafficLimitKey(identity), 0)

        override fun getTrafficAdjustment(identity: String): Flow<Float> = get(trafficAdjustmentKey(identity), 0f)

        override suspend fun setTrafficAdjustment(
            identity: String,
            value: Float,
        ) {
            dataStore.edit { it[trafficAdjustmentKey(identity)] = value }
        }

        override fun getAutoReset(identity: String): Flow<Boolean> = get(autoResetKey(identity), false)

        override fun getAutoResetDay(identity: String): Flow<Int> = get(autoResetDayKey(identity), 1)

        override fun getLastResetDate(identity: String): Flow<Long> = get(lastResetDateKey(identity), 0L)

        override suspend fun setLastResetDate(
            identity: String,
            value: Long,
        ) {
            dataStore.edit { it[lastResetDateKey(identity)] = value }
        }

        override suspend fun setAutoReset(
            identity: String,
            day: Int,
            enabled: Boolean,
        ) {
            dataStore.edit {
                it[autoResetDayKey(identity)] = day
                it[autoResetKey(identity)] = enabled
            }
        }

        override suspend fun updateSimLimits(
            identity: String,
            minutesLimit: Int,
            trafficLimitMb: Int,
        ) {
            dataStore.edit {
                it[monthlyLimitKey(identity)] = minutesLimit
                it[trafficLimitKey(identity)] = trafficLimitMb
            }
        }

        override suspend fun updateSimStats(
            identity: String,
            minutesUsed: Int,
            trafficUsed: Float,
            manualAdjustment: Int,
        ) {
            dataStore.edit {
                it[minutesUsedKey(identity)] = minutesUsed
                it[trafficUsedKey(identity)] = trafficUsed
                it[manualAdjustmentKey(identity)] = manualAdjustment
            }
        }

        override suspend fun incrementMinutesUsed(
            identity: String,
            delta: Int,
        ) {
            dataStore.edit { it[minutesUsedKey(identity)] = (it[minutesUsedKey(identity)] ?: 0) + delta }
        }

        override suspend fun incrementTrafficUsed(
            identity: String,
            deltaMb: Float,
        ) {
            dataStore.edit { it[trafficUsedKey(identity)] = (it[trafficUsedKey(identity)] ?: 0f) + deltaMb }
        }

        override suspend fun resetSimUsage(identity: String) {
            dataStore.edit {
                it[minutesUsedKey(identity)] = 0
                it[trafficUsedKey(identity)] = 0f
                it[manualAdjustmentKey(identity)] = 0
                it[trafficAdjustmentKey(identity)] = 0f
                it[lastResetDateKey(identity)] = System.currentTimeMillis()
            }
        }

        // Visibility settings (moved from SettingsRepository)
        override fun getShowSim(identity: String): Flow<Boolean> = get(SettingsKeys.showSimKey(identity), true)

        override suspend fun setShowSim(
            identity: String,
            show: Boolean,
        ) {
            dataStore.edit { it[SettingsKeys.showSimKey(identity)] = show }
        }

        override fun getShowSimIcon(identity: String): Flow<Boolean> = get(SettingsKeys.showSimIconKey(identity), true)

        override suspend fun setShowSimIcon(
            identity: String,
            show: Boolean,
        ) {
            dataStore.edit { it[SettingsKeys.showSimIconKey(identity)] = show }
        }

        override fun getShowSimName(identity: String): Flow<Boolean> = get(SettingsKeys.showSimNameKey(identity), true)

        override suspend fun setShowSimName(
            identity: String,
            show: Boolean,
        ) {
            dataStore.edit { it[SettingsKeys.showSimNameKey(identity)] = show }
        }

        override fun getShowMinutes(identity: String): Flow<Boolean> = get(SettingsKeys.showMinutesKey(identity), true)

        override suspend fun setShowMinutes(
            identity: String,
            show: Boolean,
        ) {
            dataStore.edit { it[SettingsKeys.showMinutesKey(identity)] = show }
        }

        override fun getShowMinutesLimit(identity: String): Flow<Boolean> = get(SettingsKeys.showMinutesLimitKey(identity), false)

        override suspend fun setShowMinutesLimit(
            identity: String,
            show: Boolean,
        ) {
            dataStore.edit {
                it[SettingsKeys.showMinutesLimitKey(identity)] =
                    show
            }
        }

        override fun getShowTraffic(identity: String): Flow<Boolean> = get(SettingsKeys.showTrafficKey(identity), true)

        override suspend fun setShowTraffic(
            identity: String,
            show: Boolean,
        ) {
            dataStore.edit { it[SettingsKeys.showTrafficKey(identity)] = show }
        }

        override fun getShowTrafficLimit(identity: String): Flow<Boolean> = get(SettingsKeys.showTrafficLimitKey(identity), false)

        override suspend fun setShowTrafficLimit(
            identity: String,
            show: Boolean,
        ) {
            dataStore.edit {
                it[SettingsKeys.showTrafficLimitKey(identity)] =
                    show
            }
        }

        // Notified flags (moved from SettingsRepository)
        override fun getNotifiedLowMinutes(identity: String): Flow<Boolean> = get(SettingsKeys.notifiedLowMinutesKey(identity), false)

        override suspend fun setNotifiedLowMinutes(
            identity: String,
            notified: Boolean,
        ) {
            dataStore.edit {
                it[SettingsKeys.notifiedLowMinutesKey(identity)] =
                    notified
            }
        }

        override fun getNotifiedLowTraffic(identity: String): Flow<Boolean> = get(SettingsKeys.notifiedLowTrafficKey(identity), false)

        override suspend fun setNotifiedLowTraffic(
            identity: String,
            notified: Boolean,
        ) {
            dataStore.edit {
                it[SettingsKeys.notifiedLowTrafficKey(identity)] =
                    notified
            }
        }

        override fun getNotifiedExhaustedMinutes(identity: String): Flow<Boolean> =
            get(SettingsKeys.notifiedExhaustedMinutesKey(identity), false)

        override suspend fun setNotifiedExhaustedMinutes(
            identity: String,
            notified: Boolean,
        ) {
            dataStore.edit {
                it[SettingsKeys.notifiedExhaustedMinutesKey(identity)] =
                    notified
            }
        }

        override fun getNotifiedExhaustedTraffic(identity: String): Flow<Boolean> =
            get(SettingsKeys.notifiedExhaustedTrafficKey(identity), false)

        override suspend fun setNotifiedExhaustedTraffic(
            identity: String,
            notified: Boolean,
        ) {
            dataStore.edit {
                it[SettingsKeys.notifiedExhaustedTrafficKey(identity)] =
                    notified
            }
        }

        // Tracking helpers (moved from SettingsRepository)
        override fun getLastTotalBytes(identity: String): Flow<Long> = get(SettingsKeys.lastTotalBytesKey(identity), -1L)

        override suspend fun setLastTotalBytes(
            identity: String,
            bytes: Long,
        ) {
            dataStore.edit {
                it[SettingsKeys.lastTotalBytesKey(identity)] =
                    bytes
            }
        }
    }
