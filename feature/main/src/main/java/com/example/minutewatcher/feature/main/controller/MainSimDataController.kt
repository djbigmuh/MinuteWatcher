package com.example.minutewatcher.feature.main.controller

import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.di.IoDispatcher
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.InvalidSlotException
import com.example.minutewatcher.core.domain.SeedUnavailableException
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimLimitsRepository
import com.example.minutewatcher.core.domain.SimNotFoundException
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.UsageUseCase
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.feature.main.SimCardListState
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

/**
 * MainSimDataController manages SIM card data, including updates, resets, and adjustments.
 *
 * **Thread Safety:** Safe to call from any coroutine context.
 * **Error Handling:** Propagates [CancellationException].
 * [IOException] and other operation errors are captured in [lastOperationError] for UI display.
 * Handles specific exceptions like [SeedUnavailableException] and [SimNotFoundException].
 * **AOSP DataStore:** Fully compliant with AOSP DataStore semantics for atomic updates and persistence.
 *
 * @property usageStateRepository Repository for usage state.
 * @property trackingRecoveryController Controller for tracking recovery operations.
 * @property simIdentityRepository Repository for SIM identity information.
 * @property simLimitsRepository Repository for SIM limits.
 * @property usageUseCase Use case for usage-related operations.
 * @property dataChangeNotifier Notifies about data changes (e.g., for widgets).
 * @property ioDispatcher Dispatcher for I/O bound operations.
 * @property scope Application-wide coroutine scope for async operations.
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
                    (cause as? CancellationException)?.let { throw it }
                    if ((attempt < MAX_RETRY_ATTEMPTS) && (cause is IOException)) {
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

        /**
         * Ошибка последней операции, если она завершилась неудачей.
         * UI должен отобразить эту ошибку и очистить её после показа.
         */
        data class OperationError(
            val message: String,
            val slot: Int,
        )

        private val _lastOperationError = MutableStateFlow<OperationError?>(null)
        val lastOperationError: StateFlow<OperationError?> = _lastOperationError.asStateFlow()

        /**
         * Dismisses the last operation error.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         */
        fun dismissOperationError() {
            _lastOperationError.value = null
        }

        private val _highlightedSlot = MutableStateFlow<Int?>(null)
        val highlightedSlot: StateFlow<Int?> = _highlightedSlot.asStateFlow()

        private val highlightMutex = Mutex()
        private var highlightJob: Job? = null

        /**
         * Подсвечивает карточку SIM в указанном слоте на короткое время.
         *
         * Highlights the SIM card in the specified slot for a short duration.
         *
         * @param slot SIM slot index.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         */
        fun highlightSlot(slot: Int) {
            scope.launch {
                highlightMutex.withLock {
                    // Повторный вызов для того же слота не перезапускает таймер.
                    // Это предотвращает «вечную» подсветку при быстрых повторных нажатиях.
                    // Длительность подсветки фиксирована: HIGHLIGHT_DELAY_MS от первого вызова.
                    if ((_highlightedSlot.value == slot) && (highlightJob?.isActive == true)) {
                        return@withLock
                    }
                    highlightJob?.cancel()
                    _highlightedSlot.value = slot
                    highlightJob =
                        launch {
                            delay(Constants.UI.HIGHLIGHT_DELAY_MS.milliseconds)
                            highlightMutex.withLock {
                                if (_highlightedSlot.value == slot) {
                                    _highlightedSlot.value = null
                                }
                            }
                        }
                }
            }
        }

        /**
         * After success, data change notification is triggered.
         * Errors in notification are logged and do not affect the operation result.
         * [CancellationException] is always propagated.
         *
         * @param slot Индекс слота SIM.
         * @param operationName Название операции для логирования.
         * @param block Блок операции для выполнения.
         * @return [OperationError] if the operation or notification failed, null otherwise.
         */
        private suspend fun executeSimOperation(
            slot: Int,
            operationName: String,
            block: suspend () -> Unit,
        ): OperationError? {
            return try {
                performBlockWithRetry(slot, operationName, block)
                try {
                    val widgetSuccess = notifyDataChanged(updateWidget = true, force = true)
                    if (!widgetSuccess) {
                        AppLogger.w("MainSimDataController: Data saved, but widget update failed for slot $slot")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("MainSimDataController: refresh after $operationName failed", e)
                }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleOperationException(e, slot, operationName)
            }
        }

        /**
         * Updates limits for the SIM in the specified slot.
         * ...
         */
        fun updateLimits(
            slot: Int,
            minutesLimit: Int,
            trafficLimitMb: Int,
            rolloverEnabled: Boolean = false,
        ) {
            scope.launch(ioDispatcher) {
                val error =
                    executeSimOperation(slot, "updateLimits") {
                        val identity = simIdentityRepository.getIdentity(slot)
                        simLimitsRepository.updateSimLimits(identity, minutesLimit, trafficLimitMb, rolloverEnabled)
                    }
                _lastOperationError.value = error
            }
        }

        /**
         * Resets usage statistics for the SIM in the specified slot.
         * ...
         */
        fun resetUsage(slot: Int) {
            scope.launch(ioDispatcher) {
                val error =
                    executeSimOperation(slot, "resetUsage") {
                        usageUseCase.resetCounters(slot)
                    }
                _lastOperationError.value = error
            }
        }

        /**
         * Toggles automatic limit reset for the SIM in the specified slot.
         * ...
         */
        fun toggleAutoReset(
            slot: Int,
            enabled: Boolean,
        ) {
            scope.launch(ioDispatcher) {
                val error =
                    executeSimOperation(slot, "toggleAutoReset") {
                        val identity = simIdentityRepository.getIdentity(slot)
                        val currentDay = simLimitsRepository.getAutoResetDay(identity).firstWithTimeout() ?: 1
                        simLimitsRepository.setAutoReset(identity, currentDay, enabled)
                    }
                _lastOperationError.value = error
            }
        }

        /**
         * Updates the day of the month for automatic limit reset.
         * ...
         */
        fun updateAutoResetDay(
            slot: Int,
            day: Int,
        ) {
            scope.launch(ioDispatcher) {
                val error =
                    executeSimOperation(slot, "updateAutoResetDay") {
                        val identity = simIdentityRepository.getIdentity(slot)
                        val enabled = simLimitsRepository.getAutoReset(identity).firstWithTimeout() ?: false
                        simLimitsRepository.setAutoReset(identity, day, enabled)
                    }
                _lastOperationError.value = error
            }
        }

        /**
         * Adjusts remaining minutes for the SIM in the specified slot.
         * ...
         */
        fun adjustRemainingMinutes(
            slot: Int,
            remainingMinutes: Int,
        ) {
            scope.launch(ioDispatcher) {
                val error =
                    executeSimOperation(slot, "adjustRemainingMinutes") {
                        usageUseCase.adjustMinutes(slot, remainingMinutes)
                    }
                _lastOperationError.value = error
            }
        }

        /**
         * Adjusts remaining traffic for the SIM in the specified slot.
         * ...
         */
        fun adjustRemainingTraffic(
            slot: Int,
            remainingGb: Float,
        ) {
            scope.launch(ioDispatcher) {
                val error =
                    executeSimOperation(slot, "adjustRemainingTraffic") {
                        usageUseCase.adjustTraffic(slot, remainingGb)
                    }
                _lastOperationError.value = error
            }
        }

        /**
         * Повторяет поиск установленных приложений (для AppFunctions).
         *
         * Retries app search indexing recovery.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         */
        fun retryAppSearch() {
            scope.launch { trackingRecoveryController.retryAppSearch() }
        }

        /**
         * Повторяет попытку восстановления отслеживания звонков.
         *
         * Retries call tracking recovery.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         */
        fun retryCalls() {
            scope.launch { trackingRecoveryController.retryCalls() }
        }

        /**
         * Повторяет попытку создания ярлыков.
         *
         * Retries shortcut creation recovery.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         */
        fun retryShortcuts() {
            scope.launch { trackingRecoveryController.retryShortcuts() }
        }

        /**
         * Повторяет попытку восстановления отслеживания трафика.
         *
         * Retries traffic tracking recovery.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         */
        fun retryTraffic() {
            scope.launch {
                trackingRecoveryController.retryTraffic()
                notifyDataChanged(updateWidget = true, force = true)
            }
        }

        private suspend fun performBlockWithRetry(
            slot: Int,
            operationName: String,
            block: suspend () -> Unit,
        ) {
            try {
                block()
            } catch (e: IOException) {
                AppLogger.w("IOException during $operationName for slot $slot, retrying once after 500ms...", e)
                delay(500.milliseconds)
                val success =
                    withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                        block()
                        true
                    } ?: false
                if (!success) throw IOException("Operation timed out after retry. Please check storage availability.")
            }
        }

        private fun handleOperationException(
            e: Exception,
            slot: Int,
            operationName: String,
        ): OperationError =
            when (e) {
                is InvalidSlotException -> {
                    AppLogger.e("InvalidSlotException during $operationName for slot $slot", e)
                    OperationError("Invalid SIM slot index: $slot", slot)
                }

                is SeedUnavailableException -> {
                    AppLogger.e("SeedUnavailableException during $operationName for slot $slot", e)
                    OperationError("Storage unavailable for slot ${e.slot}. Retry later.", e.slot)
                }

                is SimNotFoundException -> {
                    AppLogger.w("SimNotFoundException during $operationName for slot $slot", e)
                    OperationError("SIM was removed from slot ${e.slot}", e.slot)
                }

                is IOException -> {
                    AppLogger.e("Terminal IOException during $operationName for slot $slot after retry", e)
                    OperationError(e.message ?: "Operation failed after retry.", slot)
                }

                else -> {
                    AppLogger.e("MainSimDataController: $operationName failed for slot $slot", e)
                    OperationError(e.message ?: "Unknown error", slot)
                }
            }

        /**
         * Уведомляет систему об изменении данных для обновления виджетов и UI.
         *
         * Notifies the system that SIM data has changed.
         *
         * @param updateWidget Whether to trigger a widget update.
         * @param force Whether to force notification even if data hasn't changed.
         * @return True if the operation (and optional widget update) completed successfully.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         * **Error Handling:** Propagates [CancellationException].
         */
        suspend fun notifyDataChanged(
            updateWidget: Boolean = true,
            force: Boolean = false,
        ): Boolean =
            try {
                dataChangeNotifier.notifyDataChanged(updateWidget = updateWidget, force = force)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("MainSimDataController: notifyDataChanged failed", e)
                false
            }

        private companion object {
            private const val MAX_RETRY_ATTEMPTS = 3L
            private const val RETRY_BASE_DELAY_MS = 500L
            private const val MAX_RETRY_EXPONENT = 4 // max ~8 секунд
        }
    }
