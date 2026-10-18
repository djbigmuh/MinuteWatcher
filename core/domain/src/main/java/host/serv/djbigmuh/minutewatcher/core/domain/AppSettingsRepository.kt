package host.serv.djbigmuh.minutewatcher.core.domain

import host.serv.djbigmuh.minutewatcher.core.model.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Repository for application settings.
 *
 * All methods are safe to call from any coroutine context.
 * Implementation must comply with AOSP DataStore best practices, ensuring atomic operations.
 */
interface AppSettingsRepository {
    /**
     * Returns a flow of application settings.
     *
     * The flow does not emit any values until the settings are successfully loaded
     * from persistent storage for the first time.
     * Consumers performing one-time reads (e.g., using `first()`) should use timeouts
     * and handle potential lack of emission.
     */
    fun getAppSettings(): Flow<AppSettings>

    /**
     * Observes application settings as a StateFlow.
     */
    fun observeAppSettings(): StateFlow<AppSettings>

    /**
     * Updates application settings atomically.
     *
     * @param transform Transformation function for settings.
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    suspend fun updateAppSettings(transform: (AppSettings) -> AppSettings)
}
