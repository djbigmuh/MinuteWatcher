package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.DataRefreshNotifier
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.WidgetUpdater
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.util.SnapshotUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

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
        private val lastSnapshotHash = AtomicReference<Int?>(null)

        init {
            scope.launch {
                usageStateRepository
                    .observeSnapshot()
                    .filter { it.timestamp > Constants.Data.EMPTY_TIMESTAMP }
                    .distinctUntilChanged { old, new ->
                        SnapshotUtils.calculateContentHash(old) ==
                            SnapshotUtils.calculateContentHash(new)
                    }.debounce(Constants.Widget.DEBOUNCE_MS.milliseconds)
                    .collect { snapshot ->
                        val newHash = SnapshotUtils.calculateContentHash(snapshot)
                        if (newHash != lastSnapshotHash.get()) {
                            lastSnapshotHash.set(newHash)
                            // Обновление виджета при отсутствии активных SIM выполняется
                            // намеренно — виджет должен корректно отобразить состояние
                            // "No SIM" или "No Data".
                            widgetUpdater.updateAll()
                        }
                    }
            }
        }

        override suspend fun notifyDataChanged(
            updateWidget: Boolean,
            force: Boolean,
        ) {
            if (updateWidget && force) {
                val snapshot = usageStateRepository.observeSnapshot().value
                if (snapshot.timestamp > Constants.Data.EMPTY_TIMESTAMP) {
                    lastSnapshotHash.set(SnapshotUtils.calculateContentHash(snapshot))
                }
                widgetUpdater.updateAll()
            }
            dataRefreshNotifier.notifyRefresh(isManual = force)
        }
    }
