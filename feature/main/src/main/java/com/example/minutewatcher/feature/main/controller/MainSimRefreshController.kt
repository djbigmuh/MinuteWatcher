package com.example.minutewatcher.feature.main.controller

import com.example.minutewatcher.core.common.di.IoDispatcher
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.domain.RefreshResult
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.UsageUseCase
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
        private val timeProvider: TimeProvider,
    ) {
        private val simCheckMutex = Mutex()

        /**
         * Refreshes SIM card data internally.
         *
         * @return [RefreshResult] if update was performed, null if a refresh is already in progress.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         * **Error Handling:** Propagates [CancellationException].
         */
        suspend fun refreshSimCardsInternal(): RefreshResult? =
            withContext(ioDispatcher) {
                if (!simCheckMutex.tryLock()) {
                    AppLogger.d("Refresh already in progress, skipping")
                    return@withContext null
                }
                try {
                    try {
                        usageUseCase.refreshOperatorInfo()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.e("refreshSimCardsInternal: refreshOperatorInfo failed, continuing", e)
                    }
                    usageStateRepository.refresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Error refreshing SIM cards", e)
                    null
                } finally {
                    simCheckMutex.unlock()
                }
            }

        /**
         * Refreshes SIM card data internally and updates last refresh time.
         */
        suspend fun refreshAndNotify(
            onUpdateLastRefresh: (Long) -> Unit,
            onNotifyChanged: (Boolean) -> Unit,
            force: Boolean = false,
        ) {
            val result = refreshSimCardsInternal()
            if (result == null) {
                // Обновление уже выполняется другим вызовом — не дублируем
                return
            }
            if ((result is RefreshResult.Success) || (result is RefreshResult.Timeout)) {
                onUpdateLastRefresh(timeProvider.elapsedRealtime())
            }
            onNotifyChanged(force)
        }

        /**
         * Refreshes SIM card data and notifies listeners.
         * Used for manual pull-to-refresh.
         */
        fun refreshSimCardsNow(
            scope: CoroutineScope,
            onUpdateLastRefresh: (Long) -> Unit,
            onNotifyChanged: (Boolean) -> Unit,
            onComplete: () -> Unit = {},
        ) {
            scope.launch {
                try {
                    refreshAndNotify(onUpdateLastRefresh, onNotifyChanged, force = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("refreshSimCardsNow failed", e)
                } finally {
                    onComplete()
                }
            }
        }
    }
