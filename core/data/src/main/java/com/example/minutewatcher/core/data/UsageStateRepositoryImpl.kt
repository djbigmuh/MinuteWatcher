package com.example.minutewatcher.core.data

import android.content.Context
import com.example.minutewatcher.core.data.semantic.SemanticCapabilities
import com.example.minutewatcher.core.data.semantic.SemanticMapper
import com.example.minutewatcher.core.data.semantic.SemanticVersion
import com.example.minutewatcher.core.domain.DataRefreshNotifier
import com.example.minutewatcher.core.domain.SimRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.MinutesModel
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.SimCardUiModel
import com.example.minutewatcher.core.model.SimSnapshot
import com.example.minutewatcher.core.model.SimUsage
import com.example.minutewatcher.core.model.TrafficModel
import com.example.minutewatcher.core.model.util.SimCardMapper
import com.example.minutewatcher.core.model.util.SimUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class UsageStateRepositoryImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val simRepository: SimRepository,
        private val capabilities: SemanticCapabilities,
        private val version: SemanticVersion,
        private val callTracker: CallTracker,
        private val trafficTracker: TrafficTracker,
        private val dataRefreshNotifier: DataRefreshNotifier,
        @com.example.minutewatcher.core.data.di.ApplicationScope private val scope: CoroutineScope,
    ) : UsageStateRepository {
        override fun observeTrackingStatus(): Flow<Map<String, com.example.minutewatcher.core.model.TrackingStatus>> =
            combine(
                callTracker.status,
                trafficTracker.status,
            ) { callStatus, trafficStatus ->
                mapOf(
                    "calls" to callStatus,
                    "traffic" to trafficStatus,
                )
            }

        override fun observeSimUsages(): Flow<List<SimUsage>> =
            combine(
                simRepository.observeIdentities(),
                dataRefreshNotifier.refreshFlow,
            ) { identities, _ -> identities }
                .flatMapLatest { identities ->
                    if (identities.isEmpty()) {
                        flowOf(emptyList())
                    } else {
                        combine(identities.map { simRepository.getSimUsage(it) }) { it.toList() }
                    }
                }.map { usages ->
                    usages.filter { !SimUtils.isInactiveSim(it.operator) }
                }.distinctUntilChanged()

        override fun observeSimCardUiModels(): Flow<List<SimCardUiModel>> =
            observeSimUsages()
                .map { usages ->
                    usages.map { usage ->
                        SimCardMapper.map(context, usage.slotIndex, usage)
                    }
                }

        private val _snapshot: StateFlow<GlobalSnapshot> =
            dataRefreshNotifier.refreshFlow
                .flatMapLatest { event ->
                    observeSimUsages().map { usages ->
                        val simSnapshots =
                            usages.map { usage ->
                                val slot = usage.slotIndex
                                val operator = usage.operator
                                val isActive = operator.isNotEmpty() && !SimUtils.isInactiveSim(operator)

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
                                        PackageType.MINUTES to SemanticMapper.mapMinutes(slot, operator, minutes, usage.autoResetDay),
                                        PackageType.DATA to SemanticMapper.mapTraffic(slot, operator, traffic, usage.autoResetDay),
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
                            timestamp = System.currentTimeMillis(),
                            simSnapshots = simSnapshots,
                            capabilities = capabilities.getCapabilities(),
                            isManualUpdate = event.isManual,
                        )
                    }
                }.stateIn(scope, SharingStarted.Eagerly, createEmptySnapshot())

        override fun observeSnapshot(): StateFlow<GlobalSnapshot> = _snapshot

        override suspend fun refresh(): GlobalSnapshot = coroutineScope {
            val nextSnapshot = async { _snapshot.drop(1).first() }
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
