package com.example.minutewatcher.feature.main.controller

import com.example.minutewatcher.core.common.di.IoDispatcher
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.UsageUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import javax.inject.Inject

class MainSimRefreshController @Inject constructor(
    private val usageUseCase: UsageUseCase,
    private val usageStateRepository: UsageStateRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val simCheckMutex = Mutex()

    suspend fun refreshSimCardsInternal() =
        withContext(ioDispatcher) {
            if (!simCheckMutex.tryLock()) {
                AppLogger.d("Refresh already in progress, skipping")
                return@withContext
            }
            try {
                try {
                    usageUseCase.refreshOperatorInfo()
                    usageStateRepository.refresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Error refreshing SIM cards", e)
                }
            } finally {
                simCheckMutex.unlock()
            }
        }
}
