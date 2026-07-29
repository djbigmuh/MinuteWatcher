package com.example.minutewatcher.core.model

sealed interface RefreshState {
    object Idle : RefreshState

    object Loading : RefreshState

    object Success : RefreshState

    data class Error(
        val message: String?,
    ) : RefreshState
}
