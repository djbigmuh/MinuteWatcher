package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.domain.DataRefreshNotifier
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [DataRefreshNotifier] for broadcasting UI refresh events.
 *
 * Uses [MutableSharedFlow] with replay buffer to handle emission before subscription.
 * Safe to call from any coroutine context.
 */
@Singleton
class DataRefreshNotifierImpl
    @Inject
    constructor() : DataRefreshNotifier {
        private val _refreshFlow =
            MutableSharedFlow<DataRefreshNotifier.RefreshEvent>(
                replay = 1,
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            ).apply { tryEmit(DataRefreshNotifier.RefreshEvent()) }

        override val refreshFlow = _refreshFlow.asSharedFlow()

        /**
         * Notifies subscribers of a data refresh event.
         *
         * @param isManual True if the refresh was triggered by the user.
         * @param suppressWidgetUpdate True if widget update should be skipped.
         * Safe to call from any coroutine context.
         */
        override suspend fun notifyRefresh(
            isManual: Boolean,
            suppressWidgetUpdate: Boolean,
        ) {
            _refreshFlow.emit(DataRefreshNotifier.RefreshEvent(isManual, suppressWidgetUpdate))
        }
    }
