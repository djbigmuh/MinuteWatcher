package com.example.minutewatcher.feature.main.controller

import com.example.minutewatcher.core.common.di.IoDispatcher
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.RefreshResult
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.UsageUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
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
                    usageUseCase.refreshOperatorInfo()
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
    }
