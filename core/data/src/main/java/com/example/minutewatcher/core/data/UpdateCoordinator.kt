package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.DataRefreshNotifier
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.WidgetUpdater
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.util.SnapshotUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Единый механизм обновления.
 *
 * Координатор является Singleton и использует ApplicationScope, поэтому цикл
 * наблюдения за снимками состояния (observeSnapshot) живет в течение всего
 * времени работы приложения.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@Singleton
class UpdateCoordinator
    @Inject
    constructor(
        private val dataRefreshNotifier: DataRefreshNotifier,
        private val widgetUpdater: WidgetUpdater,
        private val usageStateRepository: UsageStateRepository,
        @ApplicationScope private val scope: CoroutineScope,
    ) : DataChangeNotifier {
        private val lastSnapshotHash = MutableStateFlow<Int?>(null)
        private var consecutiveErrors = 0

        init {
            scope.launch {
                while (isActive) {
                    try {
                        usageStateRepository
                            .observeSnapshot()
                            .filter { it.timestamp > Constants.Data.EMPTY_TIMESTAMP }
                            .distinctUntilChanged { old, new ->
                                SnapshotUtils.calculateContentHash(old) ==
                                    SnapshotUtils.calculateContentHash(new)
                            }.debounce(Constants.Widget.DEBOUNCE_MS.milliseconds)
                            .collect { snapshot ->
                                consecutiveErrors = 0
                                val newHash = SnapshotUtils.calculateContentHash(snapshot)
                                if (newHash != lastSnapshotHash.value) {
                                    lastSnapshotHash.value = newHash
                                    // Обновление виджета при отсутствии активных SIM выполняется
                                    // намеренно — виджет должен корректно отобразить состояние
                                    // "No SIM" или "No Data".
                                    widgetUpdater.updateAll()
                                }
                            }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lastSnapshotHash.value = null
                        consecutiveErrors++
                        val delaySeconds =
                            minOf(
                                INITIAL_RETRY_DELAY_SECONDS * (1L shl minOf(consecutiveErrors, MAX_RETRY_EXPONENT)),
                                MAX_RETRY_DELAY_SECONDS,
                            )
                        AppLogger.e(
                            "UpdateCoordinator: observation failed ($consecutiveErrors), retry in ${delaySeconds}s",
                            e,
                        )
                        delay(delaySeconds.seconds)
                    }
                }
            }
        }

        /**
         * Уведомляет об изменении данных.
         *
         * @param updateWidget если true — инициирует обновление данных через [DataRefreshNotifier].
         *   Если также [force] = true — виджет обновляется немедленно.
         *   Если [force] = false — обновление виджета происходит реактивно при изменении данных.
         * @param force если true — принудительное немедленное обновление виджета
         *   и пометка refresh как ручного.
         */
        override suspend fun notifyDataChanged(
            updateWidget: Boolean,
            force: Boolean,
        ) {
            if (updateWidget && force) {
                val snapshot = usageStateRepository.observeSnapshot().value
                try {
                    widgetUpdater.updateAll()
                    if (snapshot.timestamp > Constants.Data.EMPTY_TIMESTAMP) {
                        lastSnapshotHash.value = SnapshotUtils.calculateContentHash(snapshot)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.w("Force widget update failed, hash not updated", e)
                }
            }
            if (updateWidget) {
                dataRefreshNotifier.notifyRefresh(isManual = force)
            }
        }

        companion object {
            private const val INITIAL_RETRY_DELAY_SECONDS = 5L
            private const val MAX_RETRY_EXPONENT = 6
            private const val MAX_RETRY_DELAY_SECONDS = 300L
        }
    }
