package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.domain.DataRefreshNotifier
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

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

        override suspend fun notifyRefresh(
            isManual: Boolean,
            suppressWidgetUpdate: Boolean,
        ) {
            _refreshFlow.emit(DataRefreshNotifier.RefreshEvent(isManual, suppressWidgetUpdate))
        }
    }
