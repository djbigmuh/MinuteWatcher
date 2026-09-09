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

    companion object {
        const val PRIORITY_PERMISSION_MISSING = 50
        const val PRIORITY_DATABASE_ERROR = 40
        const val PRIORITY_TRANSIENT_ERROR = 30
        const val PRIORITY_HEALTHY = 20
        const val PRIORITY_INDEXING = 10
    }
}
