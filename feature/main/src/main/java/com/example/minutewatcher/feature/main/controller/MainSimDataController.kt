package com.example.minutewatcher.feature.main.controller

import androidx.annotation.StringRes
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
import com.example.minutewatcher.core.resources.R
import com.example.minutewatcher.feature.main.SimCardListState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
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
 * Consolidates all SIM-related operations and their error handling around the [executeSimOperation] mechanism.
 * The number of functions reflects the complexity of supporting various SIM card states and user actions.
 *
 * **Thread Safety:** Safe to call from any coroutine context.
 * **Error Handling:** Propagates [CancellationException].
 * [IOException] and other operation errors are captured in [lastOperationError] for UI display.
 * Handles specific exceptions like [SeedUnavailableException] and [SimNotFoundException].
 * **AOSP DataStore:** Fully compliant with AOSP DataStore semantics for atomic updates and persistence.
 *
 * NOTE: TooManyFunctions is suppressed: the controller is the command surface
 * of the SIM data feature area; its methods map 1:1 to ViewModel delegate
 * calls.
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
@Suppress("TooManyFunctions")
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
        private val retryTrigger = MutableStateFlow(0)

        @OptIn(ExperimentalCoroutinesApi::class)
        private val uiStateSource: Flow<SimCardListState> =
            retryTrigger.flatMapLatest {
                usageStateRepository
                    .observeSimCardUiModels()
                    .map { models ->
                        if (models.isEmpty()) {
                            if (usageStateRepository.isDataLoaded()) {
                                SimCardListState.Empty
                            } else {
                                SimCardListState.Loading
                            }
                        } else {
                            SimCardListState.Success(models)
                        }
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
                    .catch { e ->
                        AppLogger.e("MainSimDataController: observeSimCardUiModels failed", e)
                        emit(SimCardListState.Error(R.string.refresh_error))
                    }
            }

        /**
         * Observes the UI state of SIM cards.
         *
         * @param scope The scope in which to maintain the state flow.
         * Typically [androidx.lifecycle.viewModelScope].
         */
        fun observeUiState(scope: CoroutineScope): StateFlow<SimCardListState> =
            uiStateSource.stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(Constants.Flow.WHILE_SUBSCRIBED_STOP_TIMEOUT_MS),
                initialValue = SimCardListState.Loading,
            )

        /**
         * Triggers a restart of SIM card list observation.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         */
        fun retrySimList() {
            retryTrigger.value += 1
        }

        /**
         * Error of the last operation if it failed.
         * The UI should display this error and clear it after showing.
         */
        data class OperationError(
            @StringRes val messageRes: Int,
            val slot: Int,
            val args: List<Any> = emptyList(),
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
         * Highlights the SIM card in the specified slot for a short duration.
         *
         * @param slot SIM slot index.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         */
        fun highlightSlot(slot: Int) {
            scope.launch {
                highlightMutex.withLock {
                    // Repeated calls for the same slot do not restart the timer.
                    // This prevents "endless" highlighting during rapid repeated taps.
                    // Highlight duration is fixed: HIGHLIGHT_DELAY_MS from the first call.
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
         * @param slot SIM slot index.
         * @param operationName Operation name for logging.
         * @param block Operation block to execute.
         * @return [OperationError] if the operation or notification failed, null otherwise.
         */
        private suspend fun executeSimOperation(
            slot: Int,
            operationName: String,
            block: suspend () -> Unit,
        ): OperationError? =
            try {
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

        /**
         * Updates limits for the SIM in the specified slot.
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
         */
        fun toggleAutoReset(
            slot: Int,
            enabled: Boolean,
        ) {
            scope.launch(ioDispatcher) {
                val error =
                    executeSimOperation(slot, "toggleAutoReset") {
                        val identity = simIdentityRepository.getIdentity(slot)
                        val currentDay =
                            simLimitsRepository.getAutoResetDay(identity).firstWithTimeout()
                                ?: throw IOException("Timeout reading auto-reset day for $identity")
                        simLimitsRepository.setAutoReset(identity, currentDay, enabled)
                    }
                _lastOperationError.value = error
            }
        }

        /**
         * Updates the day of the month for automatic limit reset.
         */
        fun updateAutoResetDay(
            slot: Int,
            day: Int,
        ) {
            scope.launch(ioDispatcher) {
                val error =
                    executeSimOperation(slot, "updateAutoResetDay") {
                        val identity = simIdentityRepository.getIdentity(slot)
                        val enabled =
                            simLimitsRepository.getAutoReset(identity).firstWithTimeout()
                                ?: throw IOException("Timeout reading auto-reset status for $identity")
                        simLimitsRepository.setAutoReset(identity, day, enabled)
                    }
                _lastOperationError.value = error
            }
        }

        /**
         * Adjusts remaining minutes for the SIM in the specified slot.
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
         * Retries installed apps search indexing (for AppFunctions).
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         */
        fun retryAppSearch() {
            launchSafely("retryAppSearch") {
                trackingRecoveryController.retryAppSearch()
            }
        }

        /**
         * Retries call tracking recovery.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         */
        fun retryCalls() {
            launchSafely("retryCalls") {
                trackingRecoveryController.retryCalls()
            }
        }

        /**
         * Retries shortcut creation recovery.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         */
        fun retryShortcuts() {
            launchSafely("retryShortcuts") {
                trackingRecoveryController.retryShortcuts()
            }
        }

        /**
         * Retries traffic tracking recovery.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         */
        fun retryTraffic() {
            launchSafely("retryTraffic") {
                trackingRecoveryController.retryTraffic()
                notifyDataChanged(updateWidget = true, force = true)
            }
        }

        /**
         * Retries seed resolution and SIM discovery.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         */
        fun retrySimDiscovery() {
            launchSafely("retrySimDiscovery") {
                trackingRecoveryController.retrySimDiscovery()
            }
        }

        private suspend fun performBlockWithRetry(
            slot: Int,
            operationName: String,
            block: suspend () -> Unit,
        ) {
            val firstTryException =
                try {
                    val success =
                        withTimeoutOrNull(Constants.Flow.LONG_TIMEOUT_MS.milliseconds) {
                            block()
                            true
                        }
                    if (success == null) IOException("Initial attempt timed out") else null
                } catch (e: Exception) {
                    if (e is CancellationException || e !is IOException) throw e
                    e
                }

            if (firstTryException != null) {
                AppLogger.w(
                    "IOException during $operationName for slot $slot, retrying once after ${RETRY_BASE_DELAY_MS}ms...",
                    firstTryException,
                )
                delay(RETRY_BASE_DELAY_MS.milliseconds)
                val success =
                    withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                        block()
                        true
                    } ?: false
                if (!success) {
                    throw IOException("Operation timed out after retry. Please check storage availability.")
                }
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
                    OperationError(R.string.error_invalid_sim_slot, slot, listOf(slot))
                }

                is SeedUnavailableException -> {
                    AppLogger.e("SeedUnavailableException during $operationName for slot $slot", e)
                    val errorSlot = e.slot ?: slot
                    OperationError(R.string.error_storage_unavailable, errorSlot, listOf(errorSlot))
                }

                is SimNotFoundException -> {
                    AppLogger.w("SimNotFoundException during $operationName for slot $slot", e)
                    OperationError(R.string.error_sim_removed, e.slot, listOf(e.slot))
                }

                is IOException -> {
                    AppLogger.e("Terminal IOException during $operationName for slot $slot after retry", e)
                    OperationError(R.string.error_operation_failed_retry, slot)
                }

                else -> {
                    AppLogger.e("MainSimDataController: $operationName failed for slot $slot", e)
                    OperationError(R.string.error_unknown, slot, listOf(e.message ?: "??"))
                }
            }

        /**
         * Notifies the system that SIM data has changed for widget and UI updates.
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

        private fun launchSafely(
            operationName: String,
            block: suspend () -> Unit,
        ) {
            scope.launch {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("MainSimDataController: $operationName failed", e)
                }
            }
        }

        private companion object {
            private const val MAX_RETRY_ATTEMPTS = 3L
            private const val RETRY_BASE_DELAY_MS = 500L

            /**
             * Max exponent for retry delay. With base 500ms and 3 attempts,
             * the actual max delay is 500 * 2^2 = 2000ms.
             */
            private const val MAX_RETRY_EXPONENT = 2
        }
    }
