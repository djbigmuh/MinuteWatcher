package com.example.minutewatcher.core.domain

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Interface for triggering widget updates.
 *
 * All methods are safe to call from any coroutine context.
 */
interface WidgetUpdater {
    /**
     * Triggers an update for all application widgets.
     *
     * This operation is typically bounded by a timeout (e.g., SHORT_TIMEOUT_MS).
     *
     * @return true if the update was successfully triggered.
     * @throws IOException if communication with the widget system fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun updateAll(): Boolean
}
