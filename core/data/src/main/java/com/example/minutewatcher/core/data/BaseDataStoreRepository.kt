package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Base class for DataStore repositories providing common helpers.
 */
abstract class BaseDataStoreRepository(
    protected val dataStore: DataStore<Preferences>,
) {
    /**
     * Generic helper to get a value from DataStore with a default.
     */
    protected fun <T> getData(
        key: Preferences.Key<T>,
        defaultValue: T,
    ): Flow<T> = dataStore.data.map { it[key] ?: defaultValue }
}
