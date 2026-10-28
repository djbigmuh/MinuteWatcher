package host.serv.djbigmuh.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import host.serv.djbigmuh.minutewatcher.core.common.util.firstWithTimeoutOrDefault
import host.serv.djbigmuh.minutewatcher.core.model.Constants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Base class for DataStore repositories providing common helpers.
 *
 * This class ensures AOSP DataStore compliance for child repositories.
 * Safe to call from any coroutine context.
 */
open class BaseDataStoreRepository(
    protected val dataStore: DataStore<Preferences>,
) {
    /**
     * Generic helper to get a value from DataStore with a default.
     *
     * Safe to call from any coroutine context.
     * @throws IOException if DataStore reading fails.
     */
    protected fun <T> getData(
        key: Preferences.Key<T>,
        defaultValue: T,
    ): Flow<T> = dataStore.data.map { it[key] ?: defaultValue }

    /**
     * Standard helper for single reads from DataStore with timeout and default value.
     */
    protected suspend fun <T> getValue(
        key: Preferences.Key<T>,
        defaultValue: T,
    ): T =
        dataStore.data
            .map { it[key] ?: defaultValue }
            .firstWithTimeoutOrDefault(
                timeoutMillis = Constants.Flow.SHORT_TIMEOUT_MS,
                tag = this::class.java.simpleName,
                key = key.name,
                defaultValue = defaultValue,
            )
}
