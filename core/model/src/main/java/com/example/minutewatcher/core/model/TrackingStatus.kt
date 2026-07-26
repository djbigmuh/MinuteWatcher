package com.example.minutewatcher.core.model

sealed interface TrackingStatus {
    object Active : TrackingStatus

    object Idle : TrackingStatus

    data class Error(
        val type: ErrorType,
        val message: String? = null,
    ) : TrackingStatus

    enum class ErrorType {
        PERMISSION_MISSING,
        DATABASE_ERROR,
        UNKNOWN,
    }
}
