package host.serv.djbigmuh.minutewatcher.core.domain

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
     * This operation is bounded by [host.serv.djbigmuh.minutewatcher.core.model.Constants.Time.WIDGET_UPDATE_TIMEOUT_MS].
     *
     * @return [WidgetUpdateResult] indicating the outcome of the update.
     * @throws IOException if communication with the widget system fails.
     * @throws CancellationException if the coroutine is canceled.
     */
    suspend fun updateAll(): WidgetUpdateResult

    /**
     * Resets the widget update state.
     */
    suspend fun recover()
}
