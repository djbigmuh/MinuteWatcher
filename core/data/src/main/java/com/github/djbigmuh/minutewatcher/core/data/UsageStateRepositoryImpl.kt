package com.github.djbigmuh.minutewatcher.core.data

import android.content.Context
import com.github.djbigmuh.minutewatcher.core.android.util.SimCardMapper
import com.github.djbigmuh.minutewatcher.core.common.di.ApplicationScope
import com.github.djbigmuh.minutewatcher.core.common.util.AppLogger
import com.github.djbigmuh.minutewatcher.core.common.util.observeWithRetry
import com.github.djbigmuh.minutewatcher.core.data.semantic.SemanticCapabilities
import com.github.djbigmuh.minutewatcher.core.data.semantic.SemanticVersion
import com.github.djbigmuh.minutewatcher.core.domain.DataRefreshNotifier
import com.github.djbigmuh.minutewatcher.core.domain.RefreshResult
import com.github.djbigmuh.minutewatcher.core.domain.TrackingStatusProvider
import com.github.djbigmuh.minutewatcher.core.domain.UsageStateRepository
import com.github.djbigmuh.minutewatcher.core.model.Constants
import com.github.djbigmuh.minutewatcher.core.model.GlobalSnapshot
import com.github.djbigmuh.minutewatcher.core.model.MinutesModel
import com.github.djbigmuh.minutewatcher.core.model.PackageType
import com.github.djbigmuh.minutewatcher.core.model.SimCardUiModel
import com.github.djbigmuh.minutewatcher.core.model.SimSnapshot
import com.github.djbigmuh.minutewatcher.core.model.SimUsage
import com.github.djbigmuh.minutewatcher.core.model.TrackingKey
import com.github.djbigmuh.minutewatcher.core.model.TrackingStatus
import com.github.djbigmuh.minutewatcher.core.model.TrackingStatusMachine
import com.github.djbigmuh.minutewatcher.core.model.TrafficModel
import com.github.djbigmuh.minutewatcher.core.model.util.SemanticMapper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
    private val revisionCounter = AtomicLong(0)

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
        val isManual = isNewEvent && event.isManual
        val suppress = isNewEvent && event.suppressWidgetUpdate

        return GlobalSnapshot(
            version = semanticVersion.getVersion(),
            timestamp = timestamp,
            simSnapshots = simSnapshots,
            capabilities = semanticCapabilities.getCapabilities(),
            isManualUpdate = isManual,
            suppressWidgetUpdate = suppress,
            revision = revisionCounter.incrementAndGet(),
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
        @ApplicationScope private val scope: CoroutineScope,
    ) : UsageStateRepository,
        TrackingStatusProvider {
        override val key: TrackingKey = TrackingKey.USAGE_STATE

        private val statusMachine = TrackingStatusMachine()
        override val status: StateFlow<TrackingStatus> = statusMachine.status

        private fun transitionStatus(
            newStatus: TrackingStatus,
            force: Boolean = false,
        ) {
            statusMachine.transition(newStatus, force)
        }

        private val isDataLoadedSet = AtomicBoolean(false)
        private val isDataLoadedFlow = MutableStateFlow(false)
        private val assembler = SnapshotAssembler()

        override fun isDataLoaded(): Boolean = isDataLoadedSet.get()

        private fun markDataLoaded() {
            if (isDataLoadedSet.compareAndSet(false, true)) {
                isDataLoadedFlow.value = true
            }
        }

        private val sharedSimUsagesFlow: StateFlow<List<SimUsage>> =
            flow {
                deps.simIdentityRepository
                    .observeResolvedActiveSims()
                    .debounce(Constants.Flow.DEBOUNCE_MS.milliseconds)
                    .distinctUntilChanged()
                    .flatMapLatest { resolvedSims ->
                        when {
                            resolvedSims == null -> emptyFlow()
                            resolvedSims.isEmpty() -> {
                                markDataLoaded()
                                flowOf(emptyList())
                            }

                            else -> {
                                combine(
                                    resolvedSims.map { sim ->
                                        deps.simUsageRepository.getSimUsage(sim.identity.stableId).map { usage ->
                                            usage.copy(slotIndex = sim.slotIndex)
                                        }
                                    },
                                ) { usagesList ->
                                    markDataLoaded()
                                    usagesList.toList()
                                }
                            }
                        }
                    }.collect { emit(it) }
            }.observeWithRetry(
                label = "UsageStateRepository: simUsagesFlow",
                logThreshold = LOG_THRESHOLD,
                onPersistentFailure = {
                    transitionStatus(
                        TrackingStatus.Error(
                            TrackingStatus.ErrorType.DATABASE_ERROR,
                            "Persistent DataStore error: usage observation degraded",
                        ),
                    )
                },
                onRecovered = {
                    transitionStatus(TrackingStatus.Active, force = true)
                },
                onSourceCompleted = {},
            ).stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = emptyList(),
            )

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
            combine(sharedSimUsagesFlow, isDataLoadedFlow) { usages, _ ->
                usages
                    .asSequence()
                    .filter { it.slotIndex in Constants.SimConstants.SUPPORTED_SLOT_INDICES }
                    .map { usage ->
                        SimCardMapper.map(context, usage.slotIndex, usage)
                    }.toList()
            }.distinctUntilChanged()

        private val snapshotFlow: StateFlow<GlobalSnapshot> =
            combine(
                sharedSimUsagesFlow,
                deps.dataRefreshNotifier.refreshFlow,
                isDataLoadedFlow,
            ) { usages, event, isLoaded ->
                val timestamp = if (isLoaded) deps.timeProvider.now() else Constants.Data.EMPTY_TIMESTAMP
                assembler.assemble(
                    usages = usages,
                    event = event,
                    timestamp = timestamp,
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

            val zoneId = deps.timeProvider.zoneId()
            val entities =
                mutableMapOf(
                    PackageType.MINUTES to
                        SemanticMapper.mapMinutes(
                            slot,
                            operatorName,
                            minutes,
                            usage.autoResetDay,
                            timestamp,
                            zoneId,
                        ),
                    PackageType.DATA to
                        SemanticMapper.mapTraffic(
                            slot,
                            operatorName,
                            traffic,
                            usage.autoResetDay,
                            timestamp,
                            zoneId,
                        ),
                )

            return SimSnapshot(
                slot = slot,
                identity = usage.identity,
                operatorName = operatorName,
                entities = entities,
                autoResetDay = usage.autoResetDay,
                autoReset = usage.autoReset,
                brandKey = usage.brandKey,
            )
        }

        private fun createEmptySnapshot() =
            GlobalSnapshot(
                version = deps.semanticVersion.getVersion(),
                timestamp = Constants.Data.EMPTY_TIMESTAMP,
                simSnapshots = emptyList(),
                capabilities = deps.semanticCapabilities.getCapabilities(),
                isManualUpdate = false,
                suppressWidgetUpdate = false,
                revision = 0L,
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
         * @throws CancellationException if the refresh is canceled.
         */
        override suspend fun refresh(): RefreshResult {
            val startRevision = snapshotFlow.value.revision
            val timeout = Constants.Data.SNAPSHOT_TIMEOUT_MS.milliseconds

            try {
                deps.dataRefreshNotifier.notifyRefresh(isManual = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                AppLogger.w("UsageStateRepository: notifyRefresh failed, continuing with snapshot wait", e)
            } catch (e: SecurityException) {
                AppLogger.w("UsageStateRepository: notifyRefresh failed, continuing with snapshot wait", e)
            } catch (e: IllegalStateException) {
                AppLogger.w("UsageStateRepository: notifyRefresh failed, continuing with snapshot wait", e)
            } catch (e: IllegalArgumentException) {
                AppLogger.w("UsageStateRepository: notifyRefresh failed, continuing with snapshot wait", e)
            }

            val newSnapshot =
                withTimeoutOrNull(timeout) {
                    snapshotFlow.first { it.revision > startRevision }
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

        private companion object {
            private const val LOG_THRESHOLD = 5
        }
    }
