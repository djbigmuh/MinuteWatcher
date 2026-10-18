package host.serv.djbigmuh.minutewatcher.core.model

/**
 * Represents the current tracking status of a component.
 * Transition logic is managed by [TrackingStatusMachine].
 */
sealed interface TrackingStatus {
    data object Active : TrackingStatus

    data object Idle : TrackingStatus

    /**
     * Ready tracking status with last update timestamp.
     *
     * @property lastUpdateTimestamp Timestamp of the last successful update.
     */
    data class Ready(
        val lastUpdateTimestamp: Long,
    ) : TrackingStatus

    /**
     * Error tracking status with error details.
     *
     * @property type Error type enum value.
     * @property message Human-readable error message, or null.
     * @property code Machine-readable error code, or null.
     */
    data class Error(
        val type: ErrorType,
        val message: String? = null,
        /**
         * Machine-readable reason. message is ONLY for human/logs.
         * Logic branching ONLY by type/code; message is for display and logs,
         * its format is not a contract.
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
        /** Operation did not complete within the allotted time (DataStore/system service). */
        TIMEOUT,

        /** Widget RemoteViews/Binder size limit (TransactionTooLarge). */
        WIDGET_SIZE_LIMIT,

        /** System service/capability unavailable on device. */
        SERVICE_UNAVAILABLE,
    }

    companion object {
        const val PRIORITY_PERMISSION_MISSING = 50
        const val PRIORITY_DATABASE_ERROR = 40
        const val PRIORITY_TRANSIENT_ERROR = 30
        const val PRIORITY_HEALTHY = 20
    }
}
