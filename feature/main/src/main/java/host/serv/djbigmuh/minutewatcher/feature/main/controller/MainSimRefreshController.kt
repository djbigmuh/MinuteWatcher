package host.serv.djbigmuh.minutewatcher.feature.main.controller

import host.serv.djbigmuh.minutewatcher.core.common.di.IoDispatcher
import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import host.serv.djbigmuh.minutewatcher.core.domain.RefreshResult
import host.serv.djbigmuh.minutewatcher.core.domain.UsageStateRepository
import host.serv.djbigmuh.minutewatcher.core.domain.UsageUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * MainSimRefreshController handles refreshing SIM card data.
 *
 * **Thread Safety:** Safe to call from any coroutine context.
 * **Error Handling:** Propagates [CancellationException]. Internal refresh errors are caught and logged.
 * **AOSP DataStore:** Interacts with AOSP DataStore via [UsageUseCase] and [UsageStateRepository].
 *
 * @property usageUseCase Use case for refreshing operator info.
 * @property usageStateRepository Repository for triggering usage state refresh.
 * @property ioDispatcher Dispatcher for I/O bound operations.
 */
class MainSimRefreshController
    @Inject
    constructor(
        private val usageUseCase: UsageUseCase,
        private val usageStateRepository: UsageStateRepository,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        /**
         * The outcome of a SIM card data refresh operation.
         */
        sealed interface RefreshOutcome {
            /**
             * The refresh operation was completed.
             */
            data class Completed(
                val result: RefreshResult,
            ) : RefreshOutcome

            /**
             * The refresh operation was skipped because another one is already in progress.
             */
            data object SkippedAlreadyInProgress : RefreshOutcome

            /**
             * The refresh operation failed with an exception.
             */
            data class Failed(
                val exception: Exception,
            ) : RefreshOutcome
        }

        private val simCheckMutex = Mutex()

        /**
         * Refreshes SIM card data internally.
         *
         * @return [RefreshOutcome] indicating whether the refresh was performed, skipped, or failed.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         * **Error Handling:** Propagates [CancellationException].
         */
        @Suppress("TooGenericExceptionCaught")
        suspend fun refreshSimCardsInternal(): RefreshOutcome =
            withContext(ioDispatcher) {
                if (!simCheckMutex.tryLock()) {
                    AppLogger.d("Refresh already in progress, skipping")
                    return@withContext RefreshOutcome.SkippedAlreadyInProgress
                }
                @Suppress("TooGenericExceptionCaught")
                try {
                    @Suppress("TooGenericExceptionCaught")
                    try {
                        usageUseCase.refreshOperatorInfo()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: SecurityException) {
                        AppLogger.e("refreshSimCardsInternal: SecurityException in refreshOperatorInfo", e)
                        throw e
                    } catch (e: Exception) {
                        // Operation must complete without propagating exception for operator info refresh.
                        AppLogger.e("refreshSimCardsInternal: refreshOperatorInfo failed, continuing", e)
                    }
                    val result = usageStateRepository.refresh()
                    RefreshOutcome.Completed(result)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SecurityException) {
                    AppLogger.e("SecurityException refreshing SIM cards", e)
                    RefreshOutcome.Failed(e)
                } catch (e: Exception) {
                    // Operation must complete without propagating exception for SIM cards refresh.
                    AppLogger.e("Error refreshing SIM cards", e)
                    RefreshOutcome.Failed(e)
                } finally {
                    simCheckMutex.unlock()
                }
            }

        /**
         * Refreshes SIM card data internally and updates last refresh time.
         */
        suspend fun refreshAndNotify(
            onNotifyChanged: suspend (Boolean) -> Unit,
            force: Boolean = false,
        ): Boolean =
            when (val outcome = refreshSimCardsInternal()) {
                is RefreshOutcome.SkippedAlreadyInProgress -> false
                is RefreshOutcome.Failed -> false
                is RefreshOutcome.Completed -> {
                    when (outcome.result) {
                        is RefreshResult.Success -> {
                            onNotifyChanged(force)
                            true
                        }

                        is RefreshResult.Timeout -> {
                            AppLogger.w(
                                "MainSimRefreshController: refresh timed out, " +
                                    "timestamp=${outcome.result.snapshot.timestamp}",
                            )
                            false
                        }

                        is RefreshResult.Failure -> {
                            AppLogger.e(
                                "MainSimRefreshController: refresh failed, " +
                                    "timestamp=${outcome.result.snapshot.timestamp}",
                                outcome.result.cause,
                            )
                            false
                        }
                    }
                }
            }

        /**
         * Refreshes SIM card data and notifies listeners.
         * Used for manual pull-to-refresh.
         */
        fun refreshSimCardsNow(
            scope: CoroutineScope,
            onNotifyChanged: suspend (Boolean) -> Unit,
            onSuccess: () -> Unit = {},
            onComplete: () -> Unit = {},
        ) {
            scope.launch {
                @Suppress("TooGenericExceptionCaught")
                try {
                    if (refreshAndNotify(onNotifyChanged, force = false)) {
                        onSuccess()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Operation must complete without propagating exception for SIM cards manual refresh.
                    AppLogger.e("refreshSimCardsNow failed", e)
                } finally {
                    onComplete()
                }
            }
        }
    }
