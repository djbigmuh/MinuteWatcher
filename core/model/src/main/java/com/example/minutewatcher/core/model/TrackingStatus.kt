package com.example.minutewatcher.core.model

sealed interface TrackingStatus {
    data object Active : TrackingStatus

    data object Idle : TrackingStatus

    data object Indexing : TrackingStatus

    data class Ready(
        val lastUpdateTimestamp: Long,
    ) : TrackingStatus

    data class Error(
        val type: ErrorType,
        val message: String? = null,
        val slotIndex: Int? = null,
    ) : TrackingStatus

    enum class ErrorType {
        PERMISSION_MISSING,
        DATABASE_ERROR,
        INACCURATE_ATTRIBUTION,
        NOT_SUPPORTED,
        UNKNOWN,
    }
}
