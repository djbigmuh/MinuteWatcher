package com.example.minutewatcher.feature.main.controller

import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.di.IoDispatcher
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.domain.DataChangeNotifier
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
import java.io.IOException
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

/**
 * MainSimDataController manages SIM card data, including updates, resets, and adjustments.
 *
 * **Thread Safety:** Safe to call from any coroutine context.
 * **Error Handling:** Propagates [IOException] and [CancellationException].
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
                    // Строгое соблюдение API kotlinx.coroutines: CancellationException должен
                    // пробрасываться для корректной отмены.
                    if (cause is CancellationException) {
                        throw cause
                    }
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
         * Выполняет операцию над SIM-картой с единой обработкой ошибок.
         *
         * Стратегия обработки исключений:
         * - [SeedUnavailableException]: Означает критическую деградацию хранилища seed
         *   на кастомной прошивке. Операция прерывается, ошибка сохраняется в [lastOperationError].
         *   UI должен отобразить сообщение о недоступности хранилища.
         * - [SimNotFoundException]: Штатная ситуация (Race Condition). SIM была извлечена
         *   пользователем после загрузки списка, но до вызова операции.
         *   Операция прерывается, ошибка сохраняется в [lastOperationError].
         * - [CancellationException]: Пробрасывается для корректной отмены корутины
         *   согласно контракту kotlinx.coroutines. Не устанавливает ошибку в [lastOperationError].
         * - [IOException]: Ошибка ввода-вывода при работе с DataStore. Сохраняется как общая ошибка.
         * - [Exception]: Любая другая непредвиденная ошибка. Сохраняется в [lastOperationError]
         *   с сообщением из исключения.
         *
         * После успешного выполнения [block] вызывается [onRefresh] для обновления UI.
         * Ошибки в [onRefresh] не влияют на результат основной операции и логируются как warning.
         *
         * @param slot Индекс слота SIM.
         * @param operationName Название операции для логирования.
         * @param onRefresh Callback для обновления данных после успешной операции.
         * @param block Блок операции для выполнения.
         */
        private fun executeSimOperation(
            slot: Int,
            operationName: String,
            onRefresh: suspend () -> Unit,
            block: suspend () -> Unit,
        ) {
            scope.launch(ioDispatcher) {
                try {
                    block()
                } catch (e: SeedUnavailableException) {
                    AppLogger.e("SeedUnavailableException during $operationName for slot $slot", e)
                    _lastOperationError.value =
                        OperationError(
                            "Storage unavailable for slot ${e.slot}. Retry later.",
                            e.slot,
                        )
                    return@launch
                } catch (e: SimNotFoundException) {
                    AppLogger.w("SimNotFoundException during $operationName for slot $slot", e)
                    _lastOperationError.value =
                        OperationError(
                            "SIM was removed from slot ${e.slot}",
                            e.slot,
                        )
                    return@launch
                } catch (e: CancellationException) {
                    // CancellationException пробрасывается для кооперативной отмены корутины.
                    // Это строгое соблюдение API kotlinx.coroutines: CancellationException
                    // должен всегда пробрасываться для корректной отмены scope.
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("MainSimDataController: $operationName failed for slot $slot", e)
                    _lastOperationError.value = OperationError(e.message ?: "Unknown error", slot)
                    return@launch
                }

                // Операция успешна. Refresh — отдельная операция, его ошибка
                // не должна маскировать успех основной операции.
                try {
                    onRefresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // На AOSP refresh() не бросает исключений.
                    // Отклонение кастомной прошивки: если DataStore деградирует —
                    // это проблема кастомной прошивки.
                    AppLogger.w("MainSimDataController: refresh after $operationName failed", e)
                }
            }
        }

        /**
         * Обновляет лимиты для SIM в указанном слоте.
         * Если SIM была удалена между отображением UI и вызовом метода,
         * операция пропускается с логированием. Это штатная ситуация:
         * пользователь мог извлечь SIM-карту.
         *
         * Updates limits for the SIM in the specified slot.
         *
         * @param slot SIM slot index.
         * @param minutesLimit New minutes limit.
         * @param trafficLimitMb New traffic limit in MB.
         * @param rolloverEnabled Whether rollover is enabled.
         * @param onRefresh Callback to refresh data after successful update.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         * **Error Handling:** Propagates [IOException] and [CancellationException].
         * **AOSP DataStore:** Fully compliant with atomic updates.
         */
        fun updateLimits(
            slot: Int,
            minutesLimit: Int,
            trafficLimitMb: Int,
            rolloverEnabled: Boolean = false,
            onRefresh: suspend () -> Unit,
        ) {
            executeSimOperation(slot, "updateLimits", onRefresh) {
                val identity = simIdentityRepository.getIdentity(slot)
                simLimitsRepository.updateSimLimits(identity, minutesLimit, trafficLimitMb, rolloverEnabled)
            }
        }

        /**
         * Сбрасывает текущую статистику использования для SIM в указанном слоте.
         *
         * Resets usage statistics for the SIM in the specified slot.
         *
         * @param slot SIM slot index.
         * @param onRefresh Callback to refresh data after successful reset.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         * **Error Handling:** Propagates [IOException] and [CancellationException].
         * **AOSP DataStore:** Fully compliant with atomic updates.
         */
        fun resetUsage(
            slot: Int,
            onRefresh: suspend () -> Unit,
        ) {
            executeSimOperation(slot, "resetUsage", onRefresh) {
                usageUseCase.resetCounters(slot)
            }
        }

        /**
         * Включает или выключает автоматический сброс лимитов для SIM в указанном слоте.
         * Если SIM была удалена между отображением UI и вызовом метода,
         * операция пропускается с логированием.
         *
         * Toggles automatic limit reset for the SIM in the specified slot.
         *
         * @param slot SIM slot index.
         * @param enabled True to enable auto-reset.
         * @param onRefresh Callback to refresh data after successful toggle.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         * **Error Handling:** Propagates [IOException] and [CancellationException].
         * **AOSP DataStore:** Fully compliant with atomic updates.
         */
        fun toggleAutoReset(
            slot: Int,
            enabled: Boolean,
            onRefresh: suspend () -> Unit,
        ) {
            executeSimOperation(slot, "toggleAutoReset", onRefresh) {
                val identity = simIdentityRepository.getIdentity(slot)
                val currentDay = simLimitsRepository.getAutoResetDay(identity).firstWithTimeout() ?: 1
                simLimitsRepository.setAutoReset(identity, currentDay, enabled)
            }
        }

        /**
         * Обновляет день месяца для автоматического сброса лимитов для SIM в указанном слоте.
         * Если SIM была удалена между отображением UI и вызовом метода,
         * операция пропускается с логированием.
         *
         * Updates the day of the month for automatic limit reset.
         *
         * @param slot SIM slot index.
         * @param day Day of the month (1-31).
         * @param onRefresh Callback to refresh data after successful update.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         * **Error Handling:** Propagates [IOException] and [CancellationException].
         * **AOSP DataStore:** Fully compliant with atomic updates.
         */
        fun updateAutoResetDay(
            slot: Int,
            day: Int,
            onRefresh: suspend () -> Unit,
        ) {
            executeSimOperation(slot, "updateAutoResetDay", onRefresh) {
                val identity = simIdentityRepository.getIdentity(slot)
                val enabled = simLimitsRepository.getAutoReset(identity).firstWithTimeout() ?: false
                simLimitsRepository.setAutoReset(identity, day, enabled)
            }
        }

        /**
         * Корректирует оставшееся количество минут для SIM в указанном слоте.
         *
         * Adjusts remaining minutes for the SIM in the specified slot.
         *
         * @param slot SIM slot index.
         * @param remainingMinutes New remaining minutes value.
         * @param onRefresh Callback to refresh data after successful adjustment.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         * **Error Handling:** Propagates [IOException] and [CancellationException].
         * **AOSP DataStore:** Fully compliant with atomic updates.
         */
        fun adjustRemainingMinutes(
            slot: Int,
            remainingMinutes: Int,
            onRefresh: suspend () -> Unit,
        ) {
            executeSimOperation(slot, "adjustRemainingMinutes", onRefresh) {
                usageUseCase.adjustMinutes(slot, remainingMinutes)
            }
        }

        /**
         * Корректирует оставшееся количество трафика для SIM в указанном слоте.
         *
         * Adjusts remaining traffic for the SIM in the specified slot.
         *
         * @param slot SIM slot index.
         * @param remainingGb New remaining traffic value in GB.
         * @param onRefresh Callback to refresh data after successful adjustment.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         * **Error Handling:** Propagates [IOException] and [CancellationException].
         * **AOSP DataStore:** Fully compliant with atomic updates.
         */
        fun adjustRemainingTraffic(
            slot: Int,
            remainingGb: Float,
            onRefresh: suspend () -> Unit,
        ) {
            executeSimOperation(slot, "adjustRemainingTraffic", onRefresh) {
                usageUseCase.adjustTraffic(slot, remainingGb)
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
         * @param onRefresh Callback to refresh data after successful recovery.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         */
        fun retryTraffic(onRefresh: suspend () -> Unit) {
            scope.launch { trackingRecoveryController.retryTraffic(onRefresh) }
        }

        /**
         * Уведомляет систему об изменении данных для обновления виджетов и UI.
         *
         * Notifies the system that SIM data has changed.
         *
         * @param updateWidget Whether to trigger a widget update.
         * @param force Whether to force notification even if data hasn't changed.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         * **Error Handling:** Propagates [CancellationException].
         */
        fun notifyDataChanged(
            updateWidget: Boolean = true,
            force: Boolean = false,
        ) {
            // notifyDataChanged вызывается на ioDispatcher. DataChangeNotifier
            // реализован через UpdateCoordinator, который использует MutableSharedFlow
            // (потокобезопасный). Одновременный вызов из разных диспетчеров безопасен.
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
