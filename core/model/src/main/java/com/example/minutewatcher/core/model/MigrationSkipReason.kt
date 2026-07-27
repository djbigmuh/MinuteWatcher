package com.example.minutewatcher.core.model

/**
 * Reasons why a data migration might be skipped.
 */
enum class MigrationSkipReason {
    /**
     * No legacy data was found in the old SharedPreferences.
     */
    NO_LEGACY_DATA,
}
