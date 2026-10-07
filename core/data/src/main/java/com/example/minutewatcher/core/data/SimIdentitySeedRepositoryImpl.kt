package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.minutewatcher.core.common.di.SimDataStore
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.firstWithTimeoutOrLog
import com.example.minutewatcher.core.data.di.DataStoreKeys
import com.example.minutewatcher.core.domain.SeedResolutionResult
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.model.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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
                withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                    dataStore.edit { preferences ->
                        preferences[DataStoreKeys.SIM_IDENTITY_SEED] = seed
                    }
                } ?: run {
                    AppLogger.w("SimIdentitySeedRepositoryImpl: setSeed timed out")
                }
                clearEphemeralStateOnPersistentRestored()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("SimIdentitySeedRepositoryImpl: Failed to set seed", e)
                throw e
            }
        }

        override suspend fun getOrCreateEphemeralSeed(generator: () -> String): String =
            seedMutex.withLock {
                _isEphemeralSeedActive.value = true
                cachedEphemeralSeed ?: generator().also { cachedEphemeralSeed = it }
            }

        override suspend fun getOrCreateSeedOrEphemeralFallback(generator: () -> String): SeedResolutionResult {
            val result = tryGetPersistentSeed(generator)
            if (result != null) return result

            AppLogger.e(
                "CRITICAL: DataStore unavailable after $MAX_RETRIES retries or timeout, using ephemeral seed. " +
                    "All SIM identities will change. Previous statistics will be orphaned.",
            )
            val seed = getOrCreateEphemeralSeed(generator)
            return SeedResolutionResult.Ephemeral(seed)
        }

        /**
         * Tries to retrieve or create a persistent seed.
         *
         * [SeedResolutionResult.Persistent] is returned ONLY if the seed is successfully
         * read from the persistent store OR successfully written to it (edit returned
         * without exception).
         */
        private suspend fun tryGetPersistentSeed(generator: () -> String): SeedResolutionResult? {
            var attempt = 0
            while (attempt < MAX_RETRIES) {
                val result =
                    try {
                        val currentSeed = readPersistentSeed()
                        if (!currentSeed.isNullOrEmpty()) {
                            SeedResolutionResult.Persistent(currentSeed)
                        } else {
                            generateAndWritePersistentSeed(generator)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        handlePersistentSeedError(attempt, e)
                        null
                    }

                if (result is SeedResolutionResult.Persistent) {
                    clearEphemeralStateOnPersistentRestored()
                    return result
                }
                attempt++
            }
            return null
        }

        private suspend fun readPersistentSeed(): String? {
            val seed =
                dataStore.data
                    .map { it[DataStoreKeys.SIM_IDENTITY_SEED] ?: "" }
                    .firstWithTimeoutOrLog(
                        timeoutMillis = Constants.Flow.SHORT_TIMEOUT_MS,
                        tag = "SimIdentitySeedRepositoryImpl",
                        key = DataStoreKeys.SIM_IDENTITY_SEED.name,
                    )
            return seed?.ifEmpty { null }
        }

        private suspend fun generateAndWritePersistentSeed(generator: () -> String): SeedResolutionResult.Persistent? {
            val newSeed = generator()
            val editOutcome: Result<Preferences>? =
                withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                    Result.success(
                        dataStore.edit { preferences ->
                            if (preferences[DataStoreKeys.SIM_IDENTITY_SEED].isNullOrEmpty()) {
                                preferences[DataStoreKeys.SIM_IDENTITY_SEED] = newSeed
                            }
                        },
                    )
                }

            val updated =
                (
                    editOutcome ?: return null.also {
                        AppLogger.w("SimIdentitySeedRepositoryImpl: DataStore write timeout during seed generation")
                    }
                    ).getOrThrow()
            val storedSeed = updated[DataStoreKeys.SIM_IDENTITY_SEED]
            return if (!storedSeed.isNullOrEmpty()) {
                AppLogger.d("SimIdentitySeedRepositoryImpl: New seed stored.")
                SeedResolutionResult.Persistent(storedSeed)
            } else {
                null
            }
        }

        private suspend fun handlePersistentSeedError(
            attempt: Int,
            e: Exception,
        ) {
            if (attempt == MAX_RETRIES - 1) {
                AppLogger.e("SimIdentitySeedRepositoryImpl: Persistent seed access failed after retries", e)
            } else {
                val delayMs = INITIAL_RETRY_DELAY_MS * (1 shl attempt)
                AppLogger.w("SimIdentitySeedRepositoryImpl: Retrying in ${delayMs}ms due to ${e.message}")
                delay(delayMs.milliseconds)
            }
        }

        private suspend fun clearEphemeralStateOnPersistentRestored() {
            seedMutex.withLock {
                if (_isEphemeralSeedActive.value || cachedEphemeralSeed != null) {
                    AppLogger.d("SimIdentitySeedRepositoryImpl: Persistent seed restored, clearing ephemeral.")
                    _isEphemeralSeedActive.value = false
                    cachedEphemeralSeed = null
                }
            }
        }

        companion object {
            private const val MAX_RETRIES = 3
            private const val INITIAL_RETRY_DELAY_MS = 500L
        }
    }
