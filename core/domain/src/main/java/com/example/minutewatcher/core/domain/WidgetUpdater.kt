package com.example.minutewatcher.core.domain

interface WidgetUpdater {
    suspend fun updateAll(): Boolean
}
