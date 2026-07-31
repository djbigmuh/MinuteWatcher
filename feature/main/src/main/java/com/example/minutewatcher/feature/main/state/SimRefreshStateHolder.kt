package com.example.minutewatcher.feature.main.state

import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.UsageUseCase
import com.example.minutewatcher.core.model.RefreshState
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.RefreshResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

class SimRefreshStateHolder(
    private val usageUseCase: UsageUseCase,
    private val usageStateRepository: UsageStateRepository,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val _refreshStatus = MutableStateFlow<RefreshState>(RefreshState.Idle)
    val refreshStatus: StateFlow<RefreshState> = _refreshStatus.asStateFlow()

    private val simCheckMutex = Mutex()

    fun consumeRefreshStatus() {
        _refreshStatus.value = RefreshState.Idle
    }

    suspend fun refreshSimCardsInternal() =
        withContext(ioDispatcher) {
            if (!simCheckMutex.tryLock()) {
                AppLogger.d("Refresh already in progress, skipping")
                return@withContext
            }
            try {
                _refreshStatus.value = RefreshState.Loading
                try {
                    usageUseCase.refreshOperatorInfo()
                    val result = usageStateRepository.refresh()
                    _refreshStatus.value = if (result is RefreshResult.Success) {
                        RefreshState.Success
                    } else {
                        RefreshState.Error("Refresh timed out")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Error refreshing SIM cards", e)
                    _refreshStatus.value = RefreshState.Error(e.message)
                }
            } finally {
                simCheckMutex.unlock()
            }
        }
}
