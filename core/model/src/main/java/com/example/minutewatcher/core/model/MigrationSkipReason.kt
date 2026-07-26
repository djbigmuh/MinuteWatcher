package com.example.minutewatcher.core.model

/**
 * Reasons why a data migration might be skipped.
 */
enum class MigrationSkipReason {
    /**
     * Required permissions (e.g. READ_PHONE_STATE) were not granted.
     */
    NO_PERMISSION,

    /**
     * No active SIM cards were found to associate legacy data with.
     */
    NO_ACTIVE_SIM,

    /**
     * No legacy data was found in the old SharedPreferences.
     */
    NO_LEGACY_DATA,
}
