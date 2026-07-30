package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.DataRefreshNotifier
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.WidgetUpdater
import com.example.minutewatcher.core.model.Constants
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
        private val updateFlow =
            MutableSharedFlow<Unit>(
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        init {
            scope.launch {
                updateFlow
                    .debounce(Constants.Widget.DEBOUNCE_MS.milliseconds)
                    .map { usageStateRepository.get().observeSnapshot().value }
                    .distinctUntilChanged { old, new ->
                        // Add hash check: only update widget if the actual data in snapshot changed.
                        // We ignore timestamp and version for this comparison.
                        old.simSnapshots == new.simSnapshots && old.capabilities == new.capabilities
                    }
                    .collect { _ ->
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
            dataRefreshNotifier.notifyRefresh(isManual = force)
            if (updateWidget) {
                if (force) {
                    widgetUpdater.updateAll()
                } else {
                    updateFlow.emit(Unit)
                }
            }
        }
    }

