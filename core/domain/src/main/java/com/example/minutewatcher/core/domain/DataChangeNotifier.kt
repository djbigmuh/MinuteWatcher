package com.example.minutewatcher.core.domain

interface DataChangeNotifier {
    /**
     * Уведомляет об изменении данных.
     */
    suspend fun notifyDataChanged(
        updateWidget: Boolean = true,
        force: Boolean = false,
    )
}
