package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.DataRefreshNotifier
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.WidgetUpdater
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.util.SnapshotUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * Единый механизм обновления.
 *
 * Координатор является Singleton и использует ApplicationScope, поэтому цикл наблюдения
 * за снимками состояния (observeSnapshot) живет в течение всего времени работы приложения
 * после первого вызова [notifyDataChanged].
 */
@OptIn(FlowPreview::class)
@Singleton
class UpdateCoordinator
    @Inject
    constructor(
        private val dataRefreshNotifier: DataRefreshNotifier,
        private val widgetUpdater: WidgetUpdater,
        private val usageStateRepository: UsageStateRepository,
        private val timeProvider: TimeProvider,
        @ApplicationScope private val scope: CoroutineScope,
    ) : DataChangeNotifier {
        private val lastManualSnapshotTimestamp = AtomicLong(-1L)

        init {
            scope.launch {
                usageStateRepository
                    .observeSnapshot()
                    .filter { it.timestamp > 0L }
                    .distinctUntilChanged { old, new ->
                        SnapshotUtils.calculateContentHash(old) ==
                            SnapshotUtils.calculateContentHash(new)
                    }.debounce(Constants.Widget.DEBOUNCE_MS.milliseconds)
                    .collect { snapshot ->
                        val lastManual = lastManualSnapshotTimestamp.getAndSet(-1L)
                        if (snapshot.isManualUpdate && lastManual != -1L) {
                            val isWithinWindow =
                                (timeProvider.now() - lastManual) <
                                    Constants.Widget.MANUAL_UPDATE_BLOCK_MS
                            if (snapshot.timestamp <= lastManual && isWithinWindow) {
                                AppLogger.d("Skipping debounced widget update after recent manual refresh")
                                return@collect
                            }
                        }
                        try {
                            widgetUpdater.updateAll()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLogger.e("Widget update failed in coordinator", e)
                        }
                    }
            }
        }

        /**
         * Уведомляет об изменении данных.
         */
        override suspend fun notifyDataChanged(
            updateWidget: Boolean,
            force: Boolean,
        ) {
            if (force) {
                if (updateWidget) {
                    val now = timeProvider.now()
                    lastManualSnapshotTimestamp.set(now)
                    widgetUpdater.updateAll()
                }
                dataRefreshNotifier.notifyRefresh(isManual = true)
            } else {
                dataRefreshNotifier.notifyRefresh(isManual = false)
                // When force = false, we rely on the reactive flow (observeSnapshot)
                // to trigger widgetUpdater.updateAll() after debounce if content changes.
            }
        }
    }
