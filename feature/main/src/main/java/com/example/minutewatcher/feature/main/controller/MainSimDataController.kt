package com.example.minutewatcher.feature.main.controller

import com.example.minutewatcher.core.common.di.IoDispatcher
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimLimitsRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.UsageUseCase
import com.example.minutewatcher.core.model.Constants
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

class MainSimDataController @Inject constructor(
    private val usageStateRepository: UsageStateRepository,
    private val simIdentityRepository: SimIdentityRepository,
    private val simLimitsRepository: SimLimitsRepository,
    private val usageUseCase: UsageUseCase,
    private val dataChangeNotifier: DataChangeNotifier,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private lateinit var scope: CoroutineScope

    fun initialize(scope: CoroutineScope) {
        this.scope = scope
    }

    val uiState: StateFlow<SimCardListState> by lazy {
        usageStateRepository
            .observeSimCardUiModels()
            .map { models ->
                if (models.isEmpty()) SimCardListState.Empty else SimCardListState.Success(models)
            }.onStart { emit(SimCardListState.Loading) }
            .catch { e -> emit(SimCardListState.Error(e.message ?: "Unknown error")) }
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(Constants.Flow.STATE_FLOW_STOP_TIMEOUT_MS),
                initialValue = SimCardListState.Loading,
            )
    }

    private val _highlightedSlot = MutableStateFlow<Int?>(null)
    val highlightedSlot: StateFlow<Int?> = _highlightedSlot.asStateFlow()

    private var highlightJob: Job? = null

    fun highlightSlot(slot: Int) {
        highlightJob?.cancel()
        _highlightedSlot.value = slot
        highlightJob = scope.launch {
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

    fun resetUsage(slot: Int, onRefresh: suspend () -> Unit) {
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
            val currentState = uiState.value
            val currentDay =
                if (currentState is SimCardListState.Success) {
                    currentState.simCards.find { it.slot == slot }?.autoResetDay ?: 1
                } else {
                    simLimitsRepository.getAutoResetDay(identity).firstWithTimeout() ?: 1
                }
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
            val currentState = uiState.value
            val enabled =
                if (currentState is SimCardListState.Success) {
                    currentState.simCards.find { it.slot == slot }?.autoResetEnabled ?: false
                } else {
                    simLimitsRepository.getAutoReset(identity).firstWithTimeout() ?: false
                }
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

    fun retryAppSearch() {
        usageStateRepository.recoverTracking("appsearch")
    }

    fun retryCalls() {
        usageStateRepository.recoverTracking("calls")
    }

    fun retryShortcuts() {
        usageStateRepository.recoverTracking("shortcuts")
    }

    fun retryTraffic(onRefresh: suspend () -> Unit) {
        usageStateRepository.recoverTracking("traffic")
        scope.launch { onRefresh() }
    }

    fun notifyDataChanged(force: Boolean = false) {
        scope.launch {
            dataChangeNotifier.notifyDataChanged(force = force)
        }
    }

    companion object {
        private const val HIGHLIGHT_DELAY_SECONDS = 3L
    }
}
