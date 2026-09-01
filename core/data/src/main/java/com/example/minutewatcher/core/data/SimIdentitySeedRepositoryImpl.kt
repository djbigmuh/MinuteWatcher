package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.data.di.AppDataStore
import com.example.minutewatcher.core.data.di.DataStoreKeys
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.model.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

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

    private val _isEphemeralSeedActive = MutableStateFlow(value = false)
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
                // 1. Recovery attempt if currently ephemeral
                if (_isEphemeralSeedActive.value) {
                    val recovered = tryGetPersistentSeed(generator)
                    if (recovered != null) {
                        _isEphemeralSeedActive.value = false
                        cachedEphemeralSeed = null
                        AppLogger.d("Seed recovered from DataStore after ephemeral fallback")
                        return@withLock recovered
                    }
                }

                // 2. Normal path (or first attempt after recovery failed)
                var currentSeed = tryGetPersistentSeed(generator)

                // 3. Retry once if failed with IO or Timeout
                if (currentSeed == null) {
                    AppLogger.w("SimIdentitySeedRepositoryImpl: Persistent seed access failed, retrying in 500ms...")
                    kotlinx.coroutines.delay(RETRY_DELAY_MS.milliseconds)
                    currentSeed = tryGetPersistentSeed(generator)
                }

                if (currentSeed != null) {
                    _isEphemeralSeedActive.value = false
                    cachedEphemeralSeed = null
                    currentSeed
                } else {
                    // 4. Fallback to ephemeral if persistent failed twice
                    AppLogger.e(
                        "CRITICAL: DataStore unavailable after retry, using ephemeral seed. " +
                            "All SIM identities will change. Previous statistics will be orphaned.",
                    )
                    _isEphemeralSeedActive.value = true
                    // cachedEphemeralSeed is only accessed under seedMutex
                    cachedEphemeralSeed ?: generator().also { cachedEphemeralSeed = it }
                }
            }

    private suspend fun tryGetPersistentSeed(generator: () -> String): String? =
        try {
            withTimeout(Constants.Flow.LONG_TIMEOUT_MS.milliseconds) {
                dataStore.getOrCreateSeedInternal(generator)
            }
        } catch (_: TimeoutCancellationException) {
            AppLogger.w("SimIdentitySeedRepositoryImpl: Timeout accessing seed in DataStore")
            null
        } catch (e: java.io.IOException) {
            AppLogger.w("SimIdentitySeedRepositoryImpl: IOException accessing seed in DataStore", e)
            null
        }

    private suspend fun DataStore<Preferences>.getOrCreateSeedInternal(generator: () -> String): String {
        val existing = this.data.map { it[DataStoreKeys.SIM_IDENTITY_SEED] }.first()

        if (!existing.isNullOrEmpty()) return existing

        val newSeed = generator()
        this.edit { preferences ->
            preferences[DataStoreKeys.SIM_IDENTITY_SEED] = newSeed
        }
        return newSeed
    }

    private companion object {
        private const val RETRY_DELAY_MS = 500L
    }
    }
