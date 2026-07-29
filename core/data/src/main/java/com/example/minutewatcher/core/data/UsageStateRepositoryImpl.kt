package com.example.minutewatcher.core.data

import android.content.Context
import com.example.minutewatcher.core.data.semantic.SemanticCapabilities
import com.example.minutewatcher.core.data.semantic.SemanticMapper
import com.example.minutewatcher.core.data.semantic.SemanticVersion
import com.example.minutewatcher.core.domain.DataRefreshNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.MinutesModel
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.SimCardUiModel
import com.example.minutewatcher.core.model.SimSnapshot
import com.example.minutewatcher.core.model.SimUsage
import com.example.minutewatcher.core.model.TrafficModel
import com.example.minutewatcher.core.android.util.SimCardMapper
import com.example.minutewatcher.core.common.util.TimeProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class UsageStateRepositoryImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val simIdentityRepository: SimIdentityRepository,
        private val simUsageRepository: SimUsageRepository,
        private val capabilities: SemanticCapabilities,
        private val version: SemanticVersion,
        private val statusProviders: Set<@JvmSuppressWildcards TrackingStatusProvider>,
        private val dataRefreshNotifier: DataRefreshNotifier,
        @com.example.minutewatcher.core.common.di.ApplicationScope private val scope: CoroutineScope,
        private val timeProvider: TimeProvider,
    ) : UsageStateRepository {
        override fun recoverTracking(key: String) {
            statusProviders.find { it.key == key }?.recover()
        }

        override fun observeTrackingStatus(): Flow<Map<String, com.example.minutewatcher.core.model.TrackingStatus>> {
            val flows =
                statusProviders.map { provider ->
                    provider.status.map { status -> provider.key to status }
                }
            return combine(flows) { pairs ->
                pairs.toMap()
            }
        }

        override fun observeSimUsages(): Flow<List<SimUsage>> =
            combine(
                simIdentityRepository.observeIdentities(),
                dataRefreshNotifier.refreshFlow,
            ) { identities, _ -> identities }
                .flatMapLatest { identities ->
                    if (identities.isEmpty()) {
                        flowOf(emptyList())
                    } else {
                        combine(identities.map { simUsageRepository.getSimUsage(it) }) { it.toList() }
                    }
                }.distinctUntilChanged()

        override fun observeSimCardUiModels(): Flow<List<SimCardUiModel>> =
            observeSimUsages()
                .map { usages ->
                    usages.map { usage ->
                        SimCardMapper.map(context, usage.slotIndex, usage)
                    }
                }

        private val snapshotFlow: StateFlow<GlobalSnapshot> =
            dataRefreshNotifier.refreshFlow
                .flatMapLatest { event ->
                    observeSimUsages().map { usages ->
                        val timestamp = timeProvider.now()
                        val simSnapshots =
                            usages.map { usage ->
                                val slot = usage.slotIndex
                                val operator = usage.operator
                                val isActive = true // Every item in usages corresponds to an active physical SIM

                                val minutes =
                                    MinutesModel.calculate(
                                        used = usage.minutesUsed,
                                        limit = usage.minutesLimit,
                                        manualAdjustment = usage.manualAdjustment,
                                    )

                                val traffic =
                                    TrafficModel.calculate(
                                        usedMb = usage.trafficUsed,
                                        limitMb = usage.trafficLimitMb,
                                        manualAdjustmentGb = usage.trafficAdjustment,
                                    )

                                val entities =
                                    mutableMapOf(
                                        PackageType.MINUTES to SemanticMapper.mapMinutes(slot, operator, minutes, usage.autoResetDay, timestamp),
                                        PackageType.DATA to SemanticMapper.mapTraffic(slot, operator, traffic, usage.autoResetDay, timestamp),
                                    )

                                SimSnapshot(
                                    slot = slot,
                                    operator = operator,
                                    isActive = isActive,
                                    entities = entities,
                                )
                            }

                        GlobalSnapshot(
                            version = version.getVersion(),
                            timestamp = timestamp,
                            simSnapshots = simSnapshots,
                            capabilities = capabilities.getCapabilities(),
                            isManualUpdate = event.isManual,
                        )
                    }
                }.stateIn(scope, SharingStarted.Eagerly, createEmptySnapshot())

        override fun observeSnapshot(): StateFlow<GlobalSnapshot> = snapshotFlow

        override suspend fun refresh(): GlobalSnapshot =
            coroutineScope {
                val nextSnapshot = async { snapshotFlow.drop(1).first() }
                dataRefreshNotifier.notifyRefresh()
                nextSnapshot.await()
            }

        private fun createEmptySnapshot() =
            GlobalSnapshot(
                version = version.getVersion(),
                timestamp = 0L,
                simSnapshots = emptyList(),
                capabilities = capabilities.getCapabilities(),
            )
    }


