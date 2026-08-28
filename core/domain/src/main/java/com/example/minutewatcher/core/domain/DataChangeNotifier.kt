package com.example.minutewatcher.core.domain

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Notifier for data change events across the application.
 *
 * All methods are safe to call from any coroutine context.
 */
interface DataChangeNotifier {
    /**
     * Notifies that data has changed.
     *
     * @param updateWidget Whether to trigger a widget update.
     * @param force Whether to force the notification regardless of throttle.
     * @return True if the operation (and optional widget update) completed successfully.
     * @throws IOException if storage access fails during related operations.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun notifyDataChanged(
        updateWidget: Boolean = true,
        force: Boolean = false,
    ): Boolean
}
