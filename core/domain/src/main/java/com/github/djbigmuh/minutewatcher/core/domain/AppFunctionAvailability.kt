package com.github.djbigmuh.minutewatcher.core.domain

/**
 * Availability manager for AppFunctions (Assistant access).
 */
interface AppFunctionAvailability {
    /**
     * Enables or disables the app function based on user consent.
     *
     * No-op on devices without support (below Android 16+).
     * Errors are logged and not rethrown (except CancellationException).
     */
    suspend fun setAssistantAccessEnabled(enabled: Boolean)
}
