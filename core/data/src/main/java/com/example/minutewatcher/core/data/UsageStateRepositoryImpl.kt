package com.example.minutewatcher.core.data

import android.content.Context
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.data.semantic.SemanticCapabilities
import com.example.minutewatcher.core.data.semantic.SemanticMapper
import com.example.minutewatcher.core.data.semantic.SemanticVersion
import com.example.minutewatcher.core.data.util.SimCardMapper
import com.example.minutewatcher.core.domain.DataRefreshNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.MinutesModel
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.SimCardUiModel
import com.example.minutewatcher.core.model.SimSnapshot
import com.example.minutewatcher.core.model.SimUsage
import com.example.minutewatcher.core.model.TrafficModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@Singleton
class UsageStateRepositoryImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        simIdentityRepository: SimIdentityRepository,
        private val simUsageRepository: SimUsageRepository,
        private val semanticCapabilities: SemanticCapabilities,
        private val semanticVersion: SemanticVersion,
        private val dataRefreshNotifier: DataRefreshNotifier,
        @com.example.minutewatcher.core.common.di.ApplicationScope private val scope: CoroutineScope,
        private val timeProvider: TimeProvider,
    ) : UsageStateRepository {
        private val refreshMutex = Mutex()

        private val simUsagesFlow: Flow<List<SimUsage>> =
            simIdentityRepository
                .observeActiveSims()
                .debounce(Constants.Flow.DEBOUNCE_MS.milliseconds)
                .distinctUntilChanged()
                .flatMapLatest { sims ->
                    if (sims.isEmpty()) {
                        flowOf(emptyList())
                    } else {
                        combine(
                            sims.map { sim ->
                                simUsageRepository.getSimUsage(sim.identity.stableId).map { usage ->
                                    usage.copy(slotIndex = sim.slotIndex)
                                }
                            },
                        ) { it.toList() }
                    }
                }

        override fun observeSimUsages(): Flow<List<SimUsage>> =
            combine(simUsagesFlow, dataRefreshNotifier.refreshFlow) { usages, _ -> usages }

        override fun observeSimCardUiModels(): Flow<List<SimCardUiModel>> =
            observeSimUsages()
                .map { usages ->
                    usages.map { usage ->
                        SimCardMapper.map(context, usage.slotIndex, usage)
                    }
                }

        private val snapshotFlow: StateFlow<GlobalSnapshot> =
            combine(simUsagesFlow, dataRefreshNotifier.refreshFlow) { usages, event ->
                createSnapshot(usages, event)
            }.stateIn(scope, SharingStarted.Eagerly, createEmptySnapshot())

        private fun createSnapshot(
            usages: List<SimUsage>,
            event: DataRefreshNotifier.RefreshEvent,
        ): GlobalSnapshot {
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
                            PackageType.MINUTES to
                                SemanticMapper.mapMinutes(
                                    slot,
                                    operator,
                                    minutes,
                                    usage.autoResetDay,
                                    timestamp,
                                ),
                            PackageType.DATA to
                                SemanticMapper.mapTraffic(
                                    slot,
                                    operator,
                                    traffic,
                                    usage.autoResetDay,
                                    timestamp,
                                ),
                        )

                    SimSnapshot(
                        slot = slot,
                        identity = usage.identity,
                        operator = operator,
                        isActive = isActive,
                        entities = entities,
                        autoResetDay = usage.autoResetDay,
                    )
                }

            return GlobalSnapshot(
                version = semanticVersion.getVersion(),
                timestamp = timestamp,
                simSnapshots = simSnapshots,
                capabilities = semanticCapabilities.getCapabilities(),
                isManualUpdate = event.isManual,
            )
        }

        override fun observeSnapshot(): StateFlow<GlobalSnapshot> = snapshotFlow

        override suspend fun refresh(): com.example.minutewatcher.core.domain.RefreshResult =
            refreshMutex.withLock {
                coroutineScope {
                    val currentTimestamp = snapshotFlow.value.timestamp
                    val nextSnapshot =
                        async {
                            snapshotFlow.first { it.timestamp > currentTimestamp }
                        }
                    dataRefreshNotifier.notifyRefresh()
                    withTimeoutOrNull(Constants.Data.SNAPSHOT_TIMEOUT_MS.milliseconds) { nextSnapshot.await() }?.let {
                        com.example.minutewatcher.core.domain.RefreshResult
                            .Success(it)
                    } ?: run {
                        AppLogger.w(
                            "UsageStateRepository: Refresh timed out after ${Constants.Data.SNAPSHOT_TIMEOUT_MS / Constants.MILLIS_PER_SECOND}s, returning current snapshot",
                        )
                        com.example.minutewatcher.core.domain.RefreshResult
                            .Timeout(snapshotFlow.value)
                    }
                }
            }

        private fun createEmptySnapshot() =
            GlobalSnapshot(
                version = semanticVersion.getVersion(),
                timestamp = 0L,
                simSnapshots = emptyList(),
                capabilities = semanticCapabilities.getCapabilities(),
            )
    }
