package host.serv.djbigmuh.minutewatcher.core.domain

import host.serv.djbigmuh.minutewatcher.core.model.TrackingKey
import host.serv.djbigmuh.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.flow.Flow
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Provider of tracking status for a component.
 *
 * All properties and methods are safe to access from any thread/coroutine context.
 */
interface TrackingStatusProvider {
    /**
     * Unique key for the component.
     */
    val key: TrackingKey

    /**
     * Flow of the current tracking status.
     */
    val status: Flow<TrackingStatus>

    /**
     * Attempts to recover from an error state.
     *
     * @throws IOException if recovery fails due to storage or system error.
     * @throws CancellationException if the coroutine is canceled.
     */
    suspend fun recover() {}
}
