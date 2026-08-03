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
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
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
        private val timeProvider: TimeProvider,
        @ApplicationScope private val scope: CoroutineScope,
    ) : DataChangeNotifier {
        private val isObserving = MutableStateFlow(false)
        private val lastManualUpdateTimestamp = AtomicLong(0L)

        init {
            scope.launch {
                isObserving.first { it }
                usageStateRepository.get().observeSnapshot()
                    .filter { it.timestamp > 0L }
                    .debounce(Constants.Widget.DEBOUNCE_MS.milliseconds)
                    .distinctUntilChanged { old, new ->
                        SnapshotUtils.calculateContentHash(old) == SnapshotUtils.calculateContentHash(new)
                    }.collect { snapshot ->
                        if (snapshot.isManualUpdate) {
                            val now = timeProvider.elapsedRealtime()
                            if (now - lastManualUpdateTimestamp.get() < Constants.Widget.DEBOUNCE_MS * 2) {
                                AppLogger.d("Skipping debounced widget update after manual refresh")
                                return@collect
                            }
                        }
                        widgetUpdater.updateAll()
                    }
            }
        }

        private fun startObserving() {
            isObserving.value = true
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
                if (force) {
                    lastManualUpdateTimestamp.set(timeProvider.elapsedRealtime())
                }
                widgetUpdater.updateAll()
            }
        }
    }
