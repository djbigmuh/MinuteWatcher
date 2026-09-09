package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.minutewatcher.core.common.di.SimDataStore
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.data.di.DataStoreKeys
import com.example.minutewatcher.core.domain.SeedResolutionResult
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

@Singleton
internal class SimIdentitySeedRepositoryImpl
    @Inject
    constructor(
        @SimDataStore private val dataStore: DataStore<Preferences>,
    ) : SimIdentitySeedRepository {
        private val seedMutex = Mutex()
        private var cachedEphemeralSeed: String? = null

        private val _isEphemeralSeedActive = MutableStateFlow(false)
        override val isEphemeralSeedActive: StateFlow<Boolean> = _isEphemeralSeedActive.asStateFlow()

        override val seedFlow: Flow<String> =
            dataStore.data.map { preferences ->
                preferences[DataStoreKeys.SIM_IDENTITY_SEED] ?: ""
            }

        override suspend fun setSeed(seed: String) {
            try {
                dataStore.edit { preferences ->
                    preferences[DataStoreKeys.SIM_IDENTITY_SEED] = seed
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("SimIdentitySeedRepositoryImpl: Failed to set seed", e)
                throw e
            }
        }

        override suspend fun getOrCreateSeedOrEphemeralFallback(generator: () -> String): SeedResolutionResult {
            val result = tryGetPersistentSeed(generator)
            if (result != null) return result

            // Fallback to ephemeral seed under mutex (cache management only)
            return seedMutex.withLock {
                AppLogger.e(
                    "CRITICAL: DataStore unavailable after $MAX_RETRIES retries, using ephemeral seed. " +
                        "All SIM identities will change. Previous statistics will be orphaned.",
                )
                _isEphemeralSeedActive.value = true
                val seed = cachedEphemeralSeed ?: generator().also { cachedEphemeralSeed = it }
                SeedResolutionResult.Ephemeral(seed)
            }
        }

        private suspend fun tryGetPersistentSeed(generator: () -> String): SeedResolutionResult? {
            var result: SeedResolutionResult? = null
            var attempt = 0
            while (result == null && attempt < MAX_RETRIES) {
                try {
                    dataStore.edit { preferences ->
                        val currentSeed = preferences[DataStoreKeys.SIM_IDENTITY_SEED]
                        if (currentSeed.isNullOrEmpty()) {
                            val newSeed = generator()
                            preferences[DataStoreKeys.SIM_IDENTITY_SEED] = newSeed
                            AppLogger.d("SimIdentitySeedRepositoryImpl: New seed generated and stored.")
                            result = SeedResolutionResult.Persistent(newSeed)
                        } else {
                            result = SeedResolutionResult.Persistent(currentSeed)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    if (attempt == MAX_RETRIES - 1) {
                        AppLogger.e("SimIdentitySeedRepositoryImpl: Persistent seed access failed after retries", e)
                    } else {
                        val delayMs = INITIAL_RETRY_DELAY_MS * (1 shl attempt)
                        AppLogger.w("SimIdentitySeedRepositoryImpl: Retrying persistent seed access in ${delayMs}ms...")
                        delay(delayMs.milliseconds)
                    }
                }
                attempt++
            }

            if (result is SeedResolutionResult.Persistent) {
                seedMutex.withLock {
                    if (_isEphemeralSeedActive.value) {
                        AppLogger.d(
                            "SimIdentitySeedRepositoryImpl: Persistent seed restored, clearing ephemeral state.",
                        )
                        _isEphemeralSeedActive.value = false
                        cachedEphemeralSeed = null
                    }
                }
            }

            return result
        }

        companion object {
            private const val MAX_RETRIES = 3
            private const val INITIAL_RETRY_DELAY_MS = 500L
        }
    }
