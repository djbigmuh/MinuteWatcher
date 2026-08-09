package com.example.minutewatcher.core.domain

import kotlinx.coroutines.flow.Flow

interface DataRefreshNotifier {
    data class RefreshEvent(
        val isManual: Boolean = false,
        val suppressWidgetUpdate: Boolean = false,
    )

    val refreshFlow: Flow<RefreshEvent>

    suspend fun notifyRefresh(
        isManual: Boolean = false,
        suppressWidgetUpdate: Boolean = false,
    )
}
