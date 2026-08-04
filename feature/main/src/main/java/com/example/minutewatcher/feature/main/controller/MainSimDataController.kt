package com.example.minutewatcher.feature.main.controller

import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.di.IoDispatcher
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimLimitsRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.UsageUseCase
import com.example.minutewatcher.feature.main.SimCardListState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

class MainSimDataController
    @Inject
    constructor(
        usageStateRepository: UsageStateRepository,
        private val trackingRecoveryController: TrackingRecoveryController,
        private val simIdentityRepository: SimIdentityRepository,
        private val simLimitsRepository: SimLimitsRepository,
        private val usageUseCase: UsageUseCase,
        private val dataChangeNotifier: DataChangeNotifier,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        val uiState: StateFlow<SimCardListState> =
            usageStateRepository
                .observeSimCardUiModels()
                .map { models ->
                    if (models.isEmpty()) SimCardListState.Empty else SimCardListState.Success(models)
                }.onStart { emit(SimCardListState.Loading) }
                .catch { e -> emit(SimCardListState.Error(e.message ?: "Unknown error")) }
                .stateIn(
                    scope = scope,
                    started = SharingStarted.Eagerly,
                    initialValue = SimCardListState.Loading,
                )

        private val _highlightedSlot = MutableStateFlow<Int?>(null)
        val highlightedSlot: StateFlow<Int?> = _highlightedSlot.asStateFlow()

        private var highlightJob: Job? = null

        fun highlightSlot(slot: Int) {
            highlightJob?.cancel()
            _highlightedSlot.value = slot
            highlightJob =
                scope.launch {
                    delay(HIGHLIGHT_DELAY_SECONDS.seconds)
                    _highlightedSlot.value = null
                }
        }

        fun updateLimits(
            slot: Int,
            minutesLimit: Int,
            trafficLimitMb: Int,
            rolloverEnabled: Boolean = false,
            onRefresh: suspend () -> Unit,
        ) {
            scope.launch(ioDispatcher) {
                val identity = simIdentityRepository.getIdentity(slot)
                simLimitsRepository.updateSimLimits(identity, minutesLimit, trafficLimitMb, rolloverEnabled)
                onRefresh()
            }
        }

        fun resetUsage(
            slot: Int,
            onRefresh: suspend () -> Unit,
        ) {
            scope.launch(ioDispatcher) {
                usageUseCase.resetCounters(slot)
                onRefresh()
            }
        }

        fun toggleAutoReset(
            slot: Int,
            enabled: Boolean,
            onRefresh: suspend () -> Unit,
        ) {
            scope.launch(ioDispatcher) {
                val identity = simIdentityRepository.getIdentity(slot)
                val currentDay = simLimitsRepository.getAutoResetDay(identity).firstWithTimeout() ?: 1
                simLimitsRepository.setAutoReset(identity, currentDay, enabled)
                onRefresh()
            }
        }

        fun updateAutoResetDay(
            slot: Int,
            day: Int,
            onRefresh: suspend () -> Unit,
        ) {
            scope.launch(ioDispatcher) {
                val identity = simIdentityRepository.getIdentity(slot)
                val enabled = simLimitsRepository.getAutoReset(identity).firstWithTimeout() ?: false
                simLimitsRepository.setAutoReset(identity, day, enabled)
                onRefresh()
            }
        }

        fun adjustRemainingMinutes(
            slot: Int,
            remainingMinutes: Int,
            onRefresh: suspend () -> Unit,
        ) {
            scope.launch(ioDispatcher) {
                usageUseCase.adjustMinutes(slot, remainingMinutes)
                onRefresh()
            }
        }

        fun adjustRemainingTraffic(
            slot: Int,
            remainingGb: Float,
            onRefresh: suspend () -> Unit,
        ) {
            scope.launch(ioDispatcher) {
                usageUseCase.adjustTraffic(slot, remainingGb)
                onRefresh()
            }
        }

        fun retryAppSearch() = trackingRecoveryController.retryAppSearch()

        fun retryCalls() = trackingRecoveryController.retryCalls()

        fun retryShortcuts() = trackingRecoveryController.retryShortcuts()

        fun retryTraffic(onRefresh: suspend () -> Unit) =
            trackingRecoveryController.retryTraffic(scope, onRefresh)

        fun notifyDataChanged(force: Boolean = false) {
            scope.launch {
                dataChangeNotifier.notifyDataChanged(force = force)
            }
        }

        companion object {
            private const val HIGHLIGHT_DELAY_SECONDS = 3L
        }
    }
