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
 * Контроллер обновления данных SIM.
 * Выполняет обновление данных SIM через UsageStateRepository.
 *
 * @property usageUseCase Use case использования.
 * @property usageStateRepository Репозиторий состояния использования.
 * @property ioDispatcher Диспетчер для I/O операций.
 */
class MainSimRefreshController
    @Inject
    constructor(
        private val usageUseCase: UsageUseCase,
        private val usageStateRepository: UsageStateRepository,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        private val simCheckMutex = Mutex()

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
