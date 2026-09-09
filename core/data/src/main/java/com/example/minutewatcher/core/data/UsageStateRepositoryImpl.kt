package com.example.minutewatcher.core.data

import android.content.Context
import com.example.minutewatcher.core.android.util.SimCardMapper
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.data.semantic.SemanticCapabilities
import com.example.minutewatcher.core.data.semantic.SemanticVersion
import com.example.minutewatcher.core.domain.DataRefreshNotifier
import com.example.minutewatcher.core.domain.RefreshResult
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.MinutesModel
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.SemanticEntity
import com.example.minutewatcher.core.model.SimCardUiModel
import com.example.minutewatcher.core.model.SimSnapshot
import com.example.minutewatcher.core.model.SimUsage
import com.example.minutewatcher.core.model.TrafficModel
import com.example.minutewatcher.core.model.util.SemanticMapper
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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * Assembler for [GlobalSnapshot] that ensures [DataRefreshNotifier.RefreshEvent] flags are one-shot.
 */
internal class SnapshotAssembler {
    private val lastProcessedEventId = AtomicLong(-1)

    fun assemble(
        usages: List<SimUsage>,
        event: DataRefreshNotifier.RefreshEvent,
        timestamp: Long,
        semanticVersion: SemanticVersion,
        semanticCapabilities: SemanticCapabilities,
        mapSimSnapshot: (SimUsage, Long) -> SimSnapshot,
    ): GlobalSnapshot {
        val simSnapshots = usages.map { mapSimSnapshot(it, timestamp) }

        // If this event has already been used for a snapshot, clear its flags.
        // This makes isManual/suppressWidgetUpdate one-shot.
        val isNewEvent = lastProcessedEventId.getAndSet(event.eventId) != event.eventId
        val isManual = if (isNewEvent) event.isManual else false
        val suppress = if (isNewEvent) event.suppressWidgetUpdate else false

        return GlobalSnapshot(
            version = semanticVersion.getVersion(),
            timestamp = timestamp,
            simSnapshots = simSnapshots,
            capabilities = semanticCapabilities.getCapabilities(),
            isManualUpdate = isManual,
            suppressWidgetUpdate = suppress,
        )
    }
}

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
        private val isDataLoadedSet = AtomicBoolean(false)
        private val assembler = SnapshotAssembler()

        override fun isDataLoaded(): Boolean = isDataLoadedSet.get()

        private val sharedSimUsagesFlow: StateFlow<List<SimUsage>> =
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
                }.onEach { usages ->
                    if (usages.isNotEmpty()) {
                        isDataLoadedSet.set(true)
                    }
                }.catch { e ->
                    AppLogger.e("UsageStateRepository: Critical error in simUsagesFlow", e)
                    emit(emptyList())
                }.stateIn(
                    scope = scope,
                    started = SharingStarted.Eagerly,
                    initialValue = emptyList(),
                )

        /**
         * Returns a flow of all active SIM usages.
         *
         * @return [Flow] of [SimUsage] list.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         */
        override fun observeSimUsages(): Flow<List<SimUsage>> = sharedSimUsagesFlow

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
            sharedSimUsagesFlow
                .map { usages ->
                    usages
                        .asSequence()
                        .filter { it.slotIndex in Constants.SimConstants.SUPPORTED_SLOT_INDICES }
                        .map { usage ->
                            SimCardMapper.map(context, usage.slotIndex, usage)
                        }.toList()
                }

        private val snapshotFlow: StateFlow<GlobalSnapshot> =
            combine(sharedSimUsagesFlow, deps.dataRefreshNotifier.refreshFlow) { usages, event ->
                assembler.assemble(
                    usages = usages,
                    event = event,
                    timestamp = deps.timeProvider.now(),
                    semanticVersion = deps.semanticVersion,
                    semanticCapabilities = deps.semanticCapabilities,
                    mapSimSnapshot = { usage, ts -> mapSimSnapshot(usage, ts) },
                )
            }.stateIn(scope, SharingStarted.Eagerly, createEmptySnapshot())

        private fun mapSimSnapshot(
            usage: SimUsage,
            timestamp: Long,
        ): SimSnapshot {
            val slot = usage.slotIndex
            val operatorName = usage.operator

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
                mutableMapOf<PackageType, SemanticEntity>(
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
         * Uses [Constants.Data.SNAPSHOT_TIMEOUT_MS] for snapshot update detection.
         *
         * @return [RefreshResult] containing the new snapshot on success, or the previous one on timeout.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         * @throws kotlinx.coroutines.CancellationException if the refresh is cancelled.
         */
        override suspend fun refresh(): RefreshResult {
            val startTimestamp = snapshotFlow.value.timestamp
            val timeout = Constants.Data.SNAPSHOT_TIMEOUT_MS.milliseconds

            // Вызываем уведомление.
            try {
                deps.dataRefreshNotifier.notifyRefresh(isManual = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("UsageStateRepository: notifyRefresh failed, continuing with snapshot wait", e)
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
