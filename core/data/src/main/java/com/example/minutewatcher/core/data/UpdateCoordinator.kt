package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.data.di.ApplicationScope
import com.example.minutewatcher.core.domain.DataRefreshNotifier
import com.example.minutewatcher.core.domain.WidgetUpdater
import com.example.minutewatcher.core.model.Constants
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
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
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        private val updateFlow =
            MutableSharedFlow<Unit>(
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        init {
            scope.launch {
                updateFlow
                    .debounce(Constants.Widget.DEBOUNCE_MS.milliseconds)
                    .collect { _ ->
                        widgetUpdater.updateAll()
                    }
            }
        }

        /**
         * Уведомляет об изменении данных.
         */
        suspend fun notifyDataChanged(
            updateWidget: Boolean = true,
            force: Boolean = false,
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
