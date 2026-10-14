package com.example.minutewatcher.core.model

/**
 * Reasons why a data migration might be skipped.
 */
enum class MigrationSkipReason {
    /**
     * No legacy data was found in the old SharedPreferences.
     */
    NO_LEGACY_DATA,

    /**
     * Legacy data presence could not be determined due to storage unavailability
     * after exhausting all attempts; migration skipped and schema version was not marked current.
     */
    LEGACY_DATA_CHECK_FAILED,

    /**
     * Migration incomplete: migration incomplete, reason recorded for diagnostics, completion flags not set,
     * retry performed on next InitialSetupWorker run.
     */
    MIGRATION_PARTIAL_FAILURE,
}
