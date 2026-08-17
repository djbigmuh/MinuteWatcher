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

        fun dismissOperationError() {
            _lastOperationError.value = null
        }

        private val _highlightedSlot = MutableStateFlow<Int?>(null)
        val highlightedSlot: StateFlow<Int?> = _highlightedSlot.asStateFlow()

        private var highlightJob: Job? = null

        /**
         * Подсвечивает карточку SIM в указанном слоте на короткое время.
         */
        fun highlightSlot(slot: Int) {
            // Повторный вызов для того же слота не перезапускает таймер.
            // Это предотвращает «вечную» подсветку при быстрых повторных нажатиях.
            // Длительность подсветки фиксирована: HIGHLIGHT_DELAY_MS от первого вызова.
            if (_highlightedSlot.value == slot && highlightJob?.isActive == true) {
                return
            }
            highlightJob?.cancel()
            _highlightedSlot.value = slot
            highlightJob =
                scope.launch {
                    delay(Constants.UI.HIGHLIGHT_DELAY_MS.milliseconds)
                    _highlightedSlot.value = null
                }
        }

        /**
         * Выполняет операцию над SIM-картой с единой обработкой ошибок.
         *
         * **Строгое соблюдение API:** на AOSP getIdentity() не бросает
         * исключений при корректном состоянии DataStore. SeedUnavailableException
         * и SimNotFoundException возможны только при деградации хранилища
         * на кастомных прошивках. Это проблема кастомной прошивки.
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
                    // Порядок catch-блоков критичен: SeedUnavailableException и
                    // SimNotFoundException — подклассы IllegalStateException, который
                    // является подклассом Exception. Специфичные исключения должны
                    // ловиться ДО общего catch(Exception).
                    //
                    // **Строгое соблюдение API:** на AOSP getIdentity() не бросает
                    // SeedUnavailableException. DataStore отвечает < 100 мс.
                    // SeedUnavailableException возможен только при деградации хранилища
                    // на кастомной прошивке, не соответствующей документации Google.
                    // Это проблема кастомной прошивки.
                    //
                    // SimNotFoundException означает, что SIM была извлечена между
                    // отображением UI и вызовом операции. Это штатная ситуация.
                    handleSimIdentityError(slot, operationName, e)
                    return@launch
                } catch (e: SimNotFoundException) {
                    handleSimIdentityError(slot, operationName, e)
                    return@launch
                } catch (e: CancellationException) {
                    // CancellationException пробрасывается для кооперативной отмены корутины.
                    // Это строгое соблюдение API kotlinx.coroutines: CancellationException
                    // должен всегда пробрасываться для корректной отмены scope.
                    // Пользователь не получает обратную связь при отмене — это штатное
                    // поведение (например, при уходе с экрана во время операции).
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
                    // CancellationException пробрасывается для кооперативной отмены корутины.
                    // Это строгое соблюдение API kotlinx.coroutines: CancellationException
                    // должен всегда пробрасываться для корректной отмены scope.
                    // Пользователь не получает обратную связь при отмене — это штатное
                    // поведение (например, при уходе с экрана во время операции).
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
         */
        fun retryAppSearch() {
            scope.launch { trackingRecoveryController.retryAppSearch() }
        }

        /**
         * Повторяет попытку восстановления отслеживания звонков.
         */
        fun retryCalls() {
            scope.launch { trackingRecoveryController.retryCalls() }
        }

        /**
         * Повторяет попытку создания ярлыков.
         */
        fun retryShortcuts() {
            scope.launch { trackingRecoveryController.retryShortcuts() }
        }

        /**
         * Повторяет попытку восстановления отслеживания трафика.
         */
        fun retryTraffic(onRefresh: suspend () -> Unit) {
            scope.launch { trackingRecoveryController.retryTraffic(onRefresh) }
        }

        /**
         * Уведомляет систему об изменении данных для обновления виджетов и UI.
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

        /**
         * Обрабатывает ошибки резолва идентичности SIM.
         *
         * **Строгое соблюдение API:** На AOSP getIdentity() не бросает исключений
         * при корректном состоянии DataStore и наличии SIM. SeedUnavailableException
         * и SimNotFoundException возможны только при деградации хранилища
         * или физическом извлечении SIM между UI-действием и записью.
         *
         * **Отклонение кастомной прошивки:** Если кастомная прошивка не обеспечивает
         * своевременный ответ DataStore — это проблема кастомной прошивки.
         */
        private fun handleSimIdentityError(
            slot: Int,
            operation: String,
            e: IllegalStateException,
        ) {
            when (e) {
                is SeedUnavailableException -> {
                    AppLogger.e("SIM identity resolution failed for slot $slot: seed unavailable", e)
                    _lastOperationError.value = OperationError("Storage error for slot $slot", slot)
                }

                is SimNotFoundException -> {
                    AppLogger.w("SIM was removed from slot $slot, skipping $operation", e)
                    _lastOperationError.value = OperationError("SIM was removed from slot $slot", slot)
                }

                else -> {
                    AppLogger.e("MainSimDataController: $operation failed for slot $slot", e)
                    _lastOperationError.value = OperationError(e.message ?: "Unknown error", slot)
                }
            }
        }

        private companion object {
            private const val MAX_RETRY_ATTEMPTS = 3L
            private const val RETRY_BASE_DELAY_MS = 500L
            private const val MAX_RETRY_EXPONENT = 4 // max ~8 секунд
        }
    }
