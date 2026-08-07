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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * Единый механизм обновления.
 *
 * Координатор является Singleton и использует ApplicationScope, поэтому цикл наблюдения
 * за снимками состояния (observeSnapshot) живет в течение всего времени работы приложения.
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
        private var lastSnapshotHash: Int? = null

        init {
            scope.launch {
                usageStateRepository
                    .observeSnapshot()
                    .filter { it.timestamp > 0L }
                    .debounce(Constants.Widget.DEBOUNCE_MS.milliseconds)
                    .collect { snapshot ->
                        val newHash = SnapshotUtils.calculateContentHash(snapshot)
                        if (newHash != lastSnapshotHash) {
                            lastSnapshotHash = newHash
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
                // При force=true обновляем немедленно, игнорируя проверку хэша
                widgetUpdater.updateAll()
                // Обновляем хэш, чтобы избежать немедленного повторного обновления из реактивного цикла
                lastSnapshotHash = SnapshotUtils.calculateContentHash(usageStateRepository.observeSnapshot().value)
            }
            dataRefreshNotifier.notifyRefresh(isManual = force)
        }
    }
