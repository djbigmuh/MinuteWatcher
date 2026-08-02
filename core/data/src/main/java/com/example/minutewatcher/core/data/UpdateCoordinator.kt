package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.DataRefreshNotifier
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.WidgetUpdater
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.util.SnapshotUtils
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * Единый механизм обновления.
 */
@OptIn(FlowPreview::class)
@Singleton
class UpdateCoordinator
    @Inject
    constructor(
        private val dataRefreshNotifier: DataRefreshNotifier,
        private val widgetUpdater: WidgetUpdater,
        private val usageStateRepository: Lazy<UsageStateRepository>,
        @ApplicationScope private val scope: CoroutineScope,
    ) : DataChangeNotifier {
        private val isObserving = AtomicBoolean(false)

        private fun startObserving() {
            if (isObserving.getAndSet(true)) return

            scope.launch {
                usageStateRepository
                    .get()
                    .observeSnapshot()
                    .filter { it.timestamp > 0L }
                    .debounce(Constants.Widget.DEBOUNCE_MS.milliseconds)
                    .distinctUntilChanged { old, new ->
                        SnapshotUtils.calculateContentHash(old) == SnapshotUtils.calculateContentHash(new)
                    }.collect {
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
            startObserving()
            dataRefreshNotifier.notifyRefresh(isManual = force)
            if (updateWidget) {
                widgetUpdater.updateAll()
            }
        }
    }
