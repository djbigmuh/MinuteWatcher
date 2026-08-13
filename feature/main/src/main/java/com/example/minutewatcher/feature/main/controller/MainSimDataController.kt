package com.example.minutewatcher.feature.main.controller

import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.di.IoDispatcher
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimLimitsRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.UsageUseCase
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.feature.main.SimCardListState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

/**
 * Контроллер данных SIM-карт для главной страницы.
 *
 * Все операции запускаются на [ioDispatcher], т.к. связаны с I/O (DataStore).
 * Ошибки логируются и не пробрасываются в UI, чтобы единичный сбой
 * (например, таймаут DataStore на кастомной прошивке) не ронял весь экран.
 * Реализовано строгое соблюдение API: на AOSP getIdentity() не бросает
 * исключений при корректном состоянии DataStore; исключения возможны только
 * при деградации хранилища на кастомных прошивках.
 */
class MainSimDataController
    @Inject
    constructor(
        val usageStateRepository: UsageStateRepository,
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
                }.retryWhen { cause, attempt ->
                    // Строгое соблюдение API kotlinx.coroutines: CancellationException должен
                    // пробрасываться для корректной отмены. TimeoutCancellationException —
                    // подкласс CancellationException, но является retryable по контракту
                    // withTimeoutOrNull. На AOSP observeSimCardUiModels() не бросает IOException;
                    // таймаут возможен только при деградации DataStore на кастомных прошивках.
                    if (cause is CancellationException && cause !is TimeoutCancellationException) {
                        throw cause
                    }
                    if ((attempt < MAX_RETRY_ATTEMPTS) &&
                        ((cause is IOException) || (cause is TimeoutCancellationException))
                    ) {
                        val delayMs = RETRY_BASE_DELAY_MS * (1L shl minOf(attempt.toInt(), MAX_RETRY_EXPONENT))
                        delay(delayMs.milliseconds)
                        true
                    } else {
                        false
                    }
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
                    delay(Constants.UI.HIGHLIGHT_DELAY_MS.milliseconds)
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
                try {
                    val identity = simIdentityRepository.getIdentity(slot)
                    simLimitsRepository.updateSimLimits(identity, minutesLimit, trafficLimitMb, rolloverEnabled)
                    onRefresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("MainSimDataController: updateLimits failed for slot $slot", e)
                }
            }
        }

        fun resetUsage(
            slot: Int,
            onRefresh: suspend () -> Unit,
        ) {
            scope.launch(ioDispatcher) {
                try {
                    usageUseCase.resetCounters(slot)
                    onRefresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("MainSimDataController: resetUsage failed for slot $slot", e)
                }
            }
        }

        fun toggleAutoReset(
            slot: Int,
            enabled: Boolean,
            onRefresh: suspend () -> Unit,
        ) {
            scope.launch(ioDispatcher) {
                try {
                    val identity = simIdentityRepository.getIdentity(slot)
                    val currentDay = simLimitsRepository.getAutoResetDay(identity).firstWithTimeout() ?: 1
                    simLimitsRepository.setAutoReset(identity, currentDay, enabled)
                    onRefresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("MainSimDataController: toggleAutoReset failed for slot $slot", e)
                }
            }
        }

        fun updateAutoResetDay(
            slot: Int,
            day: Int,
            onRefresh: suspend () -> Unit,
        ) {
            scope.launch(ioDispatcher) {
                try {
                    val identity = simIdentityRepository.getIdentity(slot)
                    val enabled = simLimitsRepository.getAutoReset(identity).firstWithTimeout() ?: false
                    simLimitsRepository.setAutoReset(identity, day, enabled)
                    onRefresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("MainSimDataController: updateAutoResetDay failed for slot $slot", e)
                }
            }
        }

        fun adjustRemainingMinutes(
            slot: Int,
            remainingMinutes: Int,
            onRefresh: suspend () -> Unit,
        ) {
            scope.launch(ioDispatcher) {
                try {
                    usageUseCase.adjustMinutes(slot, remainingMinutes)
                    onRefresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("MainSimDataController: adjustRemainingMinutes failed for slot $slot", e)
                }
            }
        }

        fun adjustRemainingTraffic(
            slot: Int,
            remainingGb: Float,
            onRefresh: suspend () -> Unit,
        ) {
            scope.launch(ioDispatcher) {
                try {
                    usageUseCase.adjustTraffic(slot, remainingGb)
                    onRefresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("MainSimDataController: adjustRemainingTraffic failed for slot $slot", e)
                }
            }
        }

        fun retryAppSearch() {
            scope.launch { trackingRecoveryController.retryAppSearch() }
        }

        fun retryCalls() {
            scope.launch { trackingRecoveryController.retryCalls() }
        }

        fun retryShortcuts() {
            scope.launch { trackingRecoveryController.retryShortcuts() }
        }

        fun retryTraffic(onRefresh: suspend () -> Unit) {
            scope.launch { trackingRecoveryController.retryTraffic(onRefresh) }
        }

        fun notifyDataChanged(
            updateWidget: Boolean = true,
            force: Boolean = false,
        ) {
            scope.launch(ioDispatcher) {
                try {
                    dataChangeNotifier.notifyDataChanged(updateWidget = updateWidget, force = force)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("MainSimDataController: notifyDataChanged failed", e)
                }
            }
        }

        private companion object {
            private const val MAX_RETRY_ATTEMPTS = 3L
            private const val RETRY_BASE_DELAY_MS = 500L
            private const val MAX_RETRY_EXPONENT = 4 // max ~8 секунд
        }
    }
