package com.example.minutewatcher.core.data

import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.data.di.SimDataStore
import com.example.minutewatcher.core.data.util.SimIdentityUtils
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.IdentitySource
import com.example.minutewatcher.core.model.SimConstants
import com.example.minutewatcher.core.model.SimIdentity
import com.example.minutewatcher.core.android.util.PermissionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SimIdentityRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val subscriptionManager: SubscriptionManager,
    private val permissionManager: PermissionManager,
    permissionStateNotifier: PermissionStateNotifier,
    @SimDataStore private val dataStore: DataStore<Preferences>,
    private val seedRepository: SimIdentitySeedRepository,
    private val appSettingsRepository: AppSettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) : SimIdentityRepository {

    private val seedFlow: Flow<String> =
        seedRepository.getSeed()
            .map { seed ->
                if (seed.isEmpty()) {
                    seedRepository.getOrCreateSeed { java.util.UUID.randomUUID().toString() }
                } else {
                    seed
                }
            }
            .filter { it.isNotEmpty() }
            .distinctUntilChanged()

    private val physicalActiveSims: Flow<List<ActiveSimInfo>> =
        seedFlow
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

                    var isListenerRegistered = false

                    fun updateListenerRegistration() {
                        val hasPermission = permissionManager.hasPhoneStatePermission()
                        if (hasPermission && !isListenerRegistered) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                subscriptionManager.addOnSubscriptionsChangedListener(context.mainExecutor, listener)
                            } else {
                                @Suppress("DEPRECATION")
                                subscriptionManager.addOnSubscriptionsChangedListener(listener)
                            }
                            isListenerRegistered = true
                        } else if (!hasPermission && isListenerRegistered) {
                            subscriptionManager.removeOnSubscriptionsChangedListener(listener)
                            isListenerRegistered = false
                        }
                    }

                    launch {
                        permissionStateNotifier.changes.collect {
                            updateListenerRegistration()
                            if (permissionManager.hasPhoneStatePermission()) {
                                trySend(getActiveSimsInternal(seed))
                            } else {
                                trySend(emptyList())
                            }
                        }
                    }

                    // Initial fetch
                    if (permissionManager.hasPhoneStatePermission()) {
                        trySend(getActiveSimsInternal(seed))
                    }

                    awaitClose {
                        if (isListenerRegistered) {
                            subscriptionManager.removeOnSubscriptionsChangedListener(listener)
                        }
                    }
                }
            }

    private val activeSimsCache: StateFlow<List<ActiveSimInfo>> =
        combine(
            physicalActiveSims,
            appSettingsRepository.observeAppSettings(),
            seedFlow
        ) { physical, settings, seed ->
            if (settings.benchmarkSimCount > 0) {
                generateMockActiveSims(settings.benchmarkSimCount, seed)
            } else {
                physical
            }
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    private fun generateMockActiveSims(count: Int, seed: String): List<ActiveSimInfo> {
        val safeCount = count.coerceIn(0, SimConstants.MAX_SUPPORTED_SIM_SLOTS)
        return (0 until safeCount).map { i ->
            val identity = SimIdentity(
                stableId = "sim_$i",
                subscriptionId = i + 1000,
                slotIndex = i,
                source = IdentitySource.SLOT_FALLBACK,
            )

            // Note: saveSimIdentityInfo is suspend and can't be easily called here.
            // However, the identities "sim_$i" with slotIndex=i are predictable.
            // We should ensure they are saved when used.
            scope.launch { saveSimIdentityInfo(identity) }

            ActiveSimInfo(
                identity = identity,
                slotIndex = i,
                subscriptionId = identity.subscriptionId ?: (i + 1000),
                displayName = "SIM ${i + 1}",
                carrierName = "Mock Carrier ${i + 1}",
                mccMnc = "001001",
                isDefaultData = i == 0,
                isDefaultVoice = i == 0,
            )
        }
    }

    private suspend fun getSeed(): String = seedFlow.first()

    override suspend fun getIdentity(slot: Int): String = getSimIdentity(slot).stableId

    override suspend fun getSimIdentity(slot: Int): SimIdentity {
        activeSimsCache.value.find { it.slotIndex == slot }?.let {
            return it.identity
        }

        if (slot !in SimConstants.SUPPORTED_SLOT_INDICES) {
            return SimIdentity(
                stableId = "unsupported_$slot",
                subscriptionId = null,
                slotIndex = slot,
                source = IdentitySource.SLOT_FALLBACK,
            )
        }
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
        val slot = identity.slotIndex
        val subId = identity.subscriptionId

        val currentPrefs = dataStore.data.first()
        val currentSlot = currentPrefs[SimSettingsKeys.slotIndexKey(identity.stableId)]
        val currentSub = currentPrefs[SimSettingsKeys.subscriptionIdKey(identity.stableId)]

        if (currentSlot == slot && currentSub == subId) return

        dataStore.edit { prefs ->
            slot?.let { prefs[SimSettingsKeys.slotIndexKey(identity.stableId)] = it }
            subId?.let { prefs[SimSettingsKeys.subscriptionIdKey(identity.stableId)] = it }
        }
    }

    override fun observeActiveSims(): Flow<List<ActiveSimInfo>> = activeSimsCache

    private suspend fun getActiveSimsInternal(seed: String): List<ActiveSimInfo> {
        val infoList =
            permissionManager.getActiveSubscriptions()
                .filter { it.simSlotIndex in SimConstants.SUPPORTED_SLOT_INDICES }
        if (infoList.isEmpty()) return emptyList()

        val defaultDataSubId = permissionManager.getDefaultDataSubscriptionId()
        val defaultVoiceSubId = permissionManager.getDefaultVoiceSubscriptionId()

        return infoList.map { info ->
            val identity = SimIdentityUtils.createIdentityFromInfo(info, seed)
            saveSimIdentityInfo(identity)

            ActiveSimInfo(
                identity = identity,
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

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    override fun observeIdentities(): Flow<List<String>> =
        getActiveIdentities()
            .debounce(300.milliseconds)
            .distinctUntilChanged()

    @Suppress("SameParameterValue")
    private fun <T> get(
        key: Preferences.Key<T>,
        defaultValue: T,
    ): Flow<T> = dataStore.data.map { it[key] ?: defaultValue }

    override fun getSimOperator(identity: String): Flow<String> = get(SimSettingsKeys.simOperatorKey(identity), "")

    override suspend fun setSimOperator(identity: String, name: String) {
        dataStore.edit { it[SimSettingsKeys.simOperatorKey(identity)] = name }
    }

    override fun getSimCode(identity: String): Flow<String> = get(SimSettingsKeys.simCodeKey(identity), "")

    override suspend fun setSimCode(identity: String, code: String) {
        dataStore.edit { it[SimSettingsKeys.simCodeKey(identity)] = code }
    }
}
