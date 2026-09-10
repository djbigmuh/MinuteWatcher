package com.example.minutewatcher.core.model

/**
 * Represents the current tracking status of a component.
 * Transition logic is managed by [TrackingStatusMachine].
 */
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
        /**
         * Машиночитаемая причина. message — ТОЛЬКО для человека/логов.
         * Ветвление логики ТОЛЬКО по type/code; message — для отображения и логов,
         * его формат не является контрактом.
         */
        val code: ErrorCode? = null,
    ) : TrackingStatus

    enum class ErrorType {
        PERMISSION_MISSING,
        DATABASE_ERROR,
        INACCURATE_ATTRIBUTION,
        NOT_SUPPORTED,
        UNKNOWN,
    }

    enum class ErrorCode {
        /** Операция не завершилась в отведённое время (DataStore/system service). */
        TIMEOUT,

        /** RemoteViews/Binder size limit виджета (TransactionTooLarge). */
        WIDGET_SIZE_LIMIT,

        /** Системный сервис/возможность недоступны на устройстве. */
        SERVICE_UNAVAILABLE,
    }

    companion object {
        const val PRIORITY_PERMISSION_MISSING = 50
        const val PRIORITY_DATABASE_ERROR = 40
        const val PRIORITY_TRANSIENT_ERROR = 30
        const val PRIORITY_HEALTHY = 20
        const val PRIORITY_INDEXING = 10
    }
}
