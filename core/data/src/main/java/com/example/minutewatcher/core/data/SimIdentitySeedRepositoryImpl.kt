package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.data.di.AppDataStore
import com.example.minutewatcher.core.data.di.DataStoreKeys
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.model.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [SimIdentitySeedRepository] using AOSP DataStore.
 *
 * **Thread Safety:** Safe to call from any coroutine context.
 * **Error Handling:** Propagates [CancellationException].
 * **AOSP DataStore:** Fully compliant with AOSP DataStore semantics.
 */
@Singleton
class SimIdentitySeedRepositoryImpl
    @Inject
    constructor(
        @AppDataStore private val dataStore: DataStore<Preferences>,
    ) : SimIdentitySeedRepository {
        // Ключи DataStore — деталь реализации. Публичный API репозитория не должен их раскрывать.
        // Это соответствует принципу Dependency Inversion.

        private val seedMutex = Mutex()

        private val _isEphemeralSeedActive = MutableStateFlow(false)
        override val isEphemeralSeedActive: StateFlow<Boolean> = _isEphemeralSeedActive.asStateFlow()

    // Access is guarded by seedMutex
    private var cachedEphemeralSeed: String? = null

        /**
         * Flow of the identity seed string.
         *
         * Emits an empty string if not yet initialized.
         * Safe to call from any coroutine context.
         */
        override val seedFlow: Flow<String> =
            dataStore.data.map { preferences ->
                preferences[DataStoreKeys.SIM_IDENTITY_SEED] ?: ""
            }

        override suspend fun setSeed(seed: String) {
            seedMutex.withLock {
                dataStore.edit { preferences ->
                    preferences[DataStoreKeys.SIM_IDENTITY_SEED] = seed
                }
                _isEphemeralSeedActive.value = false
                cachedEphemeralSeed = null
            }
        }

        /**
         * Returns the existing seed or creates a new one atomically.
         *
         * @param generator Lambda that provides a new seed if needed.
         * @return The resulting seed string.
         * Safe to call from any coroutine context.
         */
        override suspend fun getOrCreateSeed(generator: () -> String): String =
            seedMutex.withLock {
                val isEphemeral = _isEphemeralSeedActive.value
                // Если активен эфемерный сид, пробуем вернуться к персистентному
                if (isEphemeral) {
                    try {
                        val recovered = dataStore.getOrCreateSeedInternal(generator)
                        _isEphemeralSeedActive.value = false
                        AppLogger.d("Seed recovered from DataStore after ephemeral fallback")
                        return@withLock recovered
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.w("DataStore still unavailable, continuing with ephemeral seed", e)
                    }
                }

                try {
                    val currentSeed = dataStore.getOrCreateSeedInternal(generator)
                    _isEphemeralSeedActive.value = false
                    cachedEphemeralSeed = null
                    currentSeed
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e(
                        "DataStore unreachable for seed, falling back to ephemeral session seed. " +
                            "Warning: Identity stable IDs may change on next restart, leading to data loss.",
                        e,
                    )
                    _isEphemeralSeedActive.value = true
                    // cachedEphemeralSeed is only accessed under seedMutex
                    cachedEphemeralSeed ?: generator().also { cachedEphemeralSeed = it }
                }
            }

    private suspend fun DataStore<Preferences>.getOrCreateSeedInternal(generator: () -> String): String {
        val existing =
            this.data
                .map { it[DataStoreKeys.SIM_IDENTITY_SEED] }
                .firstWithTimeout(Constants.Flow.SHORT_TIMEOUT_MS)

        if (!existing.isNullOrEmpty()) return existing

        val newSeed = generator()
        this.edit { preferences ->
            preferences[DataStoreKeys.SIM_IDENTITY_SEED] = newSeed
        }
        return newSeed
    }
    }
