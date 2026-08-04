package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
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
        @ApplicationScope private val scope: CoroutineScope,
    ) : DataChangeNotifier {
        private val lastManualSnapshotTimestamp = AtomicLong(0L)

        init {
            scope.launch {
                usageStateRepository
                    .observeSnapshot()
                    .filter { it.timestamp > 0L }
                    .distinctUntilChanged { old, new ->
                        SnapshotUtils.calculateContentHash(old) == SnapshotUtils.calculateContentHash(new)
                    }.debounce(Constants.Widget.DEBOUNCE_MS.milliseconds)
                    .collect { snapshot ->
                        if (snapshot.isManualUpdate && snapshot.timestamp <= lastManualSnapshotTimestamp.get()) {
                            AppLogger.d("Skipping debounced widget update after manual refresh")
                            return@collect
                        }
                        widgetUpdater.updateAll()
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
                    lastManualSnapshotTimestamp.set(usageStateRepository.observeSnapshot().value.timestamp)
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
