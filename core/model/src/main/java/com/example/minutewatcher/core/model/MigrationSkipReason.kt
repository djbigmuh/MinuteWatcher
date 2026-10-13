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
     * Migration was incomplete: after exhausting attempt limit, some records were not migrated.
     * Completion flags were set to prevent infinite retry loops, and the reason was recorded for diagnostics.
     */
    MIGRATION_PARTIAL_FAILURE,
}
