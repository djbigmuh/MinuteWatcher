package com.github.djbigmuh.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.github.djbigmuh.minutewatcher.core.common.util.firstWithTimeout
import com.github.djbigmuh.minutewatcher.core.data.util.DataStoreReadTimeoutException
import com.github.djbigmuh.minutewatcher.core.model.Constants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.IOException

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
     * Standard helper for single reads from DataStore with timeout.
     * Throws [DataStoreReadTimeoutException] on timeout instead of masking errors with default values.
     */
    protected suspend fun <T> getValue(
        key: Preferences.Key<T>,
        defaultValue: T,
    ): T =
        dataStore.data
            .map { it[key] ?: defaultValue }
            .firstWithTimeout(Constants.Flow.SHORT_TIMEOUT_MS)
            ?: throw DataStoreReadTimeoutException(key.name)
}
