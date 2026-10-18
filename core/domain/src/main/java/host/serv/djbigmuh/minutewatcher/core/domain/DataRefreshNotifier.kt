package host.serv.djbigmuh.minutewatcher.core.domain

import kotlinx.coroutines.flow.Flow
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Notifier for data refresh events.
 *
 * All methods are safe to call from any coroutine context.
 */
interface DataRefreshNotifier {
    /**
     * Event data for refresh notification.
     */
    data class RefreshEvent(
        val isManual: Boolean = false,
        val suppressWidgetUpdate: Boolean = false,
        val eventId: Long = 0L,
    )

    /**
     * Flow of refresh events.
     */
    val refreshFlow: Flow<RefreshEvent>

    /**
     * Triggers a refresh notification.
     *
     * @param isManual Whether the refresh was triggered manually by the user.
     * @param suppressWidgetUpdate Whether to avoid updating widgets after refresh.
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun notifyRefresh(
        isManual: Boolean = false,
        suppressWidgetUpdate: Boolean = false,
    )
}
