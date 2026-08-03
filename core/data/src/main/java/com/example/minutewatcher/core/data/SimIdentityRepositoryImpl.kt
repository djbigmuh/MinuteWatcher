package com.example.minutewatcher.core.data

import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.data.di.SimDataStore
import com.example.minutewatcher.core.data.util.SimIdentityUtils
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.IdentitySource
import com.example.minutewatcher.core.model.SimConstants
import com.example.minutewatcher.core.model.SimIdentity
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SimIdentityRepositoryImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val subscriptionManager: SubscriptionManager,
        private val permissionManager: PermissionManager,
        permissionStateNotifier: PermissionStateNotifier,
        @SimDataStore dataStore: DataStore<Preferences>,
        seedRepository: SimIdentitySeedRepository,
        appSettingsRepository: AppSettingsRepository,
        private val migrationService: IdentityMigrationService,
        private val mockSimProvider: MockSimProvider,
        @ApplicationScope private val scope: CoroutineScope,
    ) : BaseDataStoreRepository(dataStore),
        SimIdentityRepository {
        private val saveMutex = Mutex()

        private val seedFlow: Flow<String> =
            seedRepository
                .getSeed()
                .map { seed ->
                    seed.ifEmpty {
                        seedRepository.getOrCreateSeed {
                            java.util.UUID
                                .randomUUID()
                                .toString()
                        }
                    }
                }.filter { it.isNotEmpty() }
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

        @OptIn(ExperimentalCoroutinesApi::class)
        private val activeSimsCache: StateFlow<List<ActiveSimInfo>?> =
            combine(
                physicalActiveSims,
                appSettingsRepository.observeAppSettings(),
            ) { physical, settings ->
                physical to settings
            }.flatMapLatest { (physical, settings) ->
                flow {
                    val mocks = mockSimProvider.getMockSims(settings.benchmarkSimCount)
                    if (mocks != null) {
                        emit(mocks)
                    } else {
                        emit(physical)
                    }
                }
            }.stateIn(
                scope = scope,
                started = SharingStarted.Lazily,
                initialValue = null,
            )

        private suspend fun getSeed(): String = seedFlow.first()

        override suspend fun getIdentity(slot: Int): String = getSimIdentity(slot).stableId

        override suspend fun getSimIdentity(slot: Int): SimIdentity {
            val currentSims: List<ActiveSimInfo> = activeSimsCache.filterNotNull().first()
            currentSims.find { it.slotIndex == slot }?.let {
                return it.identity
            }

            if (slot !in SimConstants.SUPPORTED_SLOT_INDICES) {
                return createFallbackIdentity(slot, "unsupported_$slot")
            }

            return findActiveIdentityInternal(slot) ?: createFallbackIdentity(slot, "sim_$slot")
        }

        private suspend fun findActiveIdentityInternal(slot: Int): SimIdentity? {
            val seed = getSeed()
            return permissionManager.getActiveSubscriptions().find { it.simSlotIndex == slot }?.let {
                val resolved = SimIdentityUtils.createIdentityFromInfo(it, seed)
                saveSimIdentityInfo(resolved)
                resolved
            }
        }

        private fun createFallbackIdentity(
            slot: Int,
            stableId: String,
        ) = SimIdentity(
            stableId = stableId,
            subscriptionId = null,
            slotIndex = slot,
            source = IdentitySource.SLOT_FALLBACK,
        )

        private suspend fun saveSimIdentityInfo(identity: SimIdentity) = saveMutex.withLock {
            val slot = identity.slotIndex
            val subId = identity.subscriptionId

            val currentPrefs = dataStore.data.first()
            val currentSlot = currentPrefs[SimSettingsKeys.slotIndexKey(identity.stableId)]
            val currentSub = currentPrefs[SimSettingsKeys.subscriptionIdKey(identity.stableId)]

            if ((currentSlot == slot) && (currentSub == subId)) return@withLock

            // If this is a permanent identity (not fallback) and we haven't saved info for it yet,
            // it might be a transition from fallback sim_$slot.
            if (identity.source != IdentitySource.SLOT_FALLBACK && currentSlot == null) {
                scope.launch {
                    try {
                        migrationService.migrateLegacyData(identity)
                    } catch (e: Exception) {
                        com.example.minutewatcher.core.common.util.AppLogger.e("Failed to migrate legacy data for identity: ${identity.stableId}", e)
                    }
                }
            }

            dataStore.edit { prefs ->
                slot?.let { prefs[SimSettingsKeys.slotIndexKey(identity.stableId)] = it }
                subId?.let { prefs[SimSettingsKeys.subscriptionIdKey(identity.stableId)] = it }
            }
        }

        override fun observeActiveSims(): Flow<List<ActiveSimInfo>> = activeSimsCache.filterNotNull()

        private suspend fun getActiveSimsInternal(seed: String): List<ActiveSimInfo> {
            val infoList =
                permissionManager
                    .getActiveSubscriptions()
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
                .debounce(Constants.Flow.DEBOUNCE_MS.milliseconds)
                .distinctUntilChanged()

        override fun getSimOperator(identity: String): Flow<String> =
            combine(
                getData(SimSettingsKeys.simOperatorKey(identity), ""),
                getCustomSimOperator(identity),
            ) { resolved, custom ->
                custom ?: resolved
            }

        override fun getCustomSimOperator(identity: String): Flow<String?> =
            dataStore.data.map { it[SimSettingsKeys.customSimOperatorKey(identity)] }

        override suspend fun setSimOperator(
            identity: String,
            name: String,
        ) {
            dataStore.edit { it[SimSettingsKeys.simOperatorKey(identity)] = name }
        }

        override suspend fun setCustomSimOperator(
            identity: String,
            name: String?,
        ) {
            dataStore.edit { prefs ->
                if (name == null) {
                    prefs.remove(SimSettingsKeys.customSimOperatorKey(identity))
                } else {
                    prefs[SimSettingsKeys.customSimOperatorKey(identity)] = name
                }
            }
        }

        override fun getSimCode(identity: String): Flow<String> = getData(SimSettingsKeys.simCodeKey(identity), "")

        override suspend fun setSimCode(
            identity: String,
            code: String,
        ) {
            dataStore.edit { it[SimSettingsKeys.simCodeKey(identity)] = code }
        }
    }
