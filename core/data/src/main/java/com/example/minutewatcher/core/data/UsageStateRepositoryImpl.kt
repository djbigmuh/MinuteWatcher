package com.example.minutewatcher.core.data

import android.content.Context
import com.example.minutewatcher.core.android.util.SimCardMapper
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.data.semantic.SemanticMapper
import com.example.minutewatcher.core.domain.DataRefreshNotifier
import com.example.minutewatcher.core.domain.RefreshResult
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
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

/**
 * Implementation of [UsageStateRepository] that aggregates usage data into [GlobalSnapshot]s.
 *
 * AOSP DataStore compliant.
 * Safe to call from any coroutine context.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@Singleton
class UsageStateRepositoryImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val deps: UsageRepositoryDependencies,
        @com.example.minutewatcher.core.common.di.ApplicationScope private val scope: CoroutineScope,
    ) : UsageStateRepository {
        private val refreshMutex = Mutex()
        private var lastNotifyTimestamp = -1L

        private val simUsagesFlow: Flow<List<SimUsage>> =
            deps.simIdentityRepository
                .observeActiveSims()
                .debounce(Constants.Flow.DEBOUNCE_MS.milliseconds)
                .distinctUntilChanged()
                .flatMapLatest { sims ->
                    if (sims.isEmpty()) {
                        flowOf(emptyList())
                    } else {
                        combine(
                            sims.map { sim ->
                                deps.simUsageRepository.getSimUsage(sim.identity.stableId).map { usage ->
                                    usage.copy(slotIndex = sim.slotIndex)
                                }
                            },
                        ) { it.toList() }
                    }
                }.catch { e ->
                    AppLogger.e("UsageStateRepository: Critical error in simUsagesFlow", e)
                    emit(emptyList())
                }

        /**
         * Returns a flow of all active SIM usages.
         *
         * Combines persistent usage data with real-time refresh events.
         *
         * @return [Flow] of [SimUsage] list.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         */
        override fun observeSimUsages(): Flow<List<SimUsage>> =
            combine(simUsagesFlow, deps.dataRefreshNotifier.refreshFlow) { usages, _ -> usages }

        /**
         * Returns a flow of UI models for all active SIM cards.
         *
         * Maps [SimUsage] data to [SimCardUiModel] using [SimCardMapper].
         *
         * @return [Flow] of [SimCardUiModel] list.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         */
        override fun observeSimCardUiModels(): Flow<List<SimCardUiModel>> =
            observeSimUsages()
                .map { usages ->
                    usages.map { usage ->
                        SimCardMapper.map(context, usage.slotIndex, usage)
                    }
                }

        private val snapshotFlow: StateFlow<GlobalSnapshot> =
            combine(simUsagesFlow, deps.dataRefreshNotifier.refreshFlow) { usages, event ->
                createSnapshot(usages, event)
            }.stateIn(scope, SharingStarted.Eagerly, createEmptySnapshot())

        private fun createSnapshot(
            usages: List<SimUsage>,
            event: DataRefreshNotifier.RefreshEvent,
        ): GlobalSnapshot {
            val timestamp = deps.timeProvider.now()
            val simSnapshots = usages.map { mapSimSnapshot(it, timestamp) }

            return GlobalSnapshot(
                version = deps.semanticVersion.getVersion(),
                timestamp = timestamp,
                simSnapshots = simSnapshots,
                capabilities = deps.semanticCapabilities.getCapabilities(),
                isManualUpdate = event.isManual,
                suppressWidgetUpdate = event.suppressWidgetUpdate,
            )
        }

        private fun mapSimSnapshot(
            usage: SimUsage,
            timestamp: Long,
        ): SimSnapshot {
            val slot = usage.slotIndex
            val operatorName = usage.operator
            val isActive = true // Every item in usages corresponds to an active physical SIM

            val minutes =
                MinutesModel.calculate(
                    used = usage.minutesUsed,
                    limit = usage.minutesLimit,
                    manualAdjustment = usage.manualAdjustment,
                )

            val traffic =
                TrafficModel.calculate(
                    usedMb = usage.trafficUsedMb,
                    limitMb = usage.trafficLimitMb,
                    manualAdjustmentGb = usage.trafficAdjustmentGb,
                )

            val entities =
                mutableMapOf(
                    PackageType.MINUTES to
                        SemanticMapper.mapMinutes(
                            slot,
                            operatorName,
                            minutes,
                            usage.autoResetDay,
                            timestamp,
                        ),
                    PackageType.DATA to
                        SemanticMapper.mapTraffic(
                            slot,
                            operatorName,
                            traffic,
                            usage.autoResetDay,
                            timestamp,
                        ),
                )

            return SimSnapshot(
                slot = slot,
                identity = usage.identity,
                operatorName = operatorName,
                isActive = isActive,
                entities = entities,
                autoResetDay = usage.autoResetDay,
            )
        }

        private fun createEmptySnapshot() =
            GlobalSnapshot(
                version = deps.semanticVersion.getVersion(),
                timestamp = 0L,
                simSnapshots = emptyList(),
                capabilities = deps.semanticCapabilities.getCapabilities(),
                isManualUpdate = false,
                suppressWidgetUpdate = false,
            )

        /**
         * Returns a state flow of the latest [GlobalSnapshot].
         *
         * Aggregates usage data, semantic versions, and capabilities into a single snapshot.
         *
         * @return [StateFlow] of the latest [GlobalSnapshot].
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         */
        override fun observeSnapshot(): StateFlow<GlobalSnapshot> = snapshotFlow

        /**
         * Forcefully refreshes usage data and returns the resulting snapshot.
         *
         * Обеспечивает последовательное выполнение через [refreshMutex]. Если несколько
         * вызовов происходят одновременно, они ждут своей очереди. Если за время ожидания
         * снимок уже обновился, повторный запрос не отправляется.
         *
         * Uses [Constants.Data.SNAPSHOT_TIMEOUT_MS] for snapshot update detection.
         *
         * @return [RefreshResult] containing the new snapshot on success, or the previous one on timeout.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         * Sequential execution is enforced via [refreshMutex].
         * @throws kotlinx.coroutines.CancellationException if the refresh is cancelled.
         */
        override suspend fun refresh(): RefreshResult {
            val startTimestamp = snapshotFlow.value.timestamp
            val timeout = Constants.Data.SNAPSHOT_TIMEOUT_MS.milliseconds

            val alreadyUpdated =
                refreshMutex.withLock {
                    if (snapshotFlow.value.timestamp > startTimestamp) {
                        true
                    } else {
                        if (lastNotifyTimestamp < startTimestamp) {
                            try {
                                deps.dataRefreshNotifier.notifyRefresh(isManual = true)
                                lastNotifyTimestamp = startTimestamp
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                AppLogger.w("UsageStateRepository: notifyRefresh failed, continuing with snapshot wait", e)
                                // We still set it to avoid redundant retries for the same state
                                lastNotifyTimestamp = startTimestamp
                            }
                        }
                        false
                    }
                }

            if (alreadyUpdated) {
                return RefreshResult.Success(snapshotFlow.value)
            }

            val newSnapshot =
                withTimeoutOrNull(timeout) {
                    snapshotFlow.first { it.timestamp > startTimestamp }
                }

            return if (newSnapshot != null) {
                RefreshResult.Success(newSnapshot)
            } else {
                AppLogger.w(
                    "UsageStateRepository: refresh timed out after " +
                        "${Constants.Data.SNAPSHOT_TIMEOUT_MS}ms, returning current snapshot",
                )
                RefreshResult.Timeout(snapshotFlow.value)
            }
        }
    }
