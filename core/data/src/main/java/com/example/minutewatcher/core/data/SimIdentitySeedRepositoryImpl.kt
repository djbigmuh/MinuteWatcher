package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.minutewatcher.core.common.di.AppDataStore
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.data.di.DataStoreKeys
import com.example.minutewatcher.core.domain.SeedResolutionResult
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.SimDiscoveryResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider
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
        private val migrationService: Provider<IdentityMigrationService>,
        private val discovery: Provider<SimIdentityDiscovery>,
    ) : SimIdentitySeedRepository {
        // Ключи DataStore — деталь реализации. Публичный API репозитория не должен их раскрывать.
        // Это соответствует принципу Dependency Inversion.

        private val seedMutex = Mutex()
    private val migrationInProgress = AtomicBoolean(false)

    private val _isEphemeralSeedActive = MutableStateFlow(value = false)
        override val isEphemeralSeedActive: StateFlow<Boolean> = _isEphemeralSeedActive.asStateFlow()

    @Volatile
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
         * **Important:** If DataStore is unavailable after repeated attempts, returns an ephemeral
         * seed which is NOT persisted. SIM identities will be unstable until storage access is restored.
         *
         * @param generator Lambda that provides a new seed if needed.
         * @return The resulting seed resolution result.
         * Safe to call from any coroutine context.
         */
        override suspend fun getOrCreateSeedOrEphemeralFallback(generator: () -> String): SeedResolutionResult {
            repeat(MAX_RETRIES) { attempt ->
                val attemptNum = attempt + 1
                try {
                    val persistentSeed = seedMutex.withLock { tryGetPersistentSeed(generator) }
                    if (persistentSeed != null) {
                        val ephemeralToMigrate = resolveEphemeralToMigrateInternal()
                        ephemeralToMigrate?.let { seed ->
                            if (migrationInProgress.compareAndSet(false, true)) {
                                try {
                                    executeMigrationSafely(seed, persistentSeed)
                                } finally {
                                    migrationInProgress.set(false)
                                }
                            }
                        }
                        return SeedResolutionResult.Persistent(persistentSeed)
                    }
                    AppLogger.w(
                        "SimIdentitySeedRepositoryImpl: Persistent seed access returned null (attempt $attemptNum)",
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.w(
                        "SimIdentitySeedRepositoryImpl: Persistent seed access failed (attempt $attemptNum)",
                        e
                    )
                }

                if (attemptNum < MAX_RETRIES) {
                    val delayMs = INITIAL_RETRY_DELAY_MS * (1 shl attempt)
                    AppLogger.w("SimIdentitySeedRepositoryImpl: Retrying persistent seed access in ${delayMs}ms...")
                    delay(delayMs.milliseconds)
                }
            }

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

    private suspend fun resolveEphemeralToMigrateInternal(): String? {
        var needsStorageCheck = false
        seedMutex.withLock {
            if (_isEphemeralSeedActive.value || cachedEphemeralSeed != null) {
                val found = cachedEphemeralSeed
                _isEphemeralSeedActive.value = false
                cachedEphemeralSeed = null
                if (found != null) {
                    return found
                }
                needsStorageCheck = true
            }
        }

        if (needsStorageCheck) {
            return tryGetStoredEphemeralSeed()
        }
        return null
    }

    private suspend fun tryGetStoredEphemeralSeed(): String? =
        try {
            dataStore.data
                .map { it[DataStoreKeys.EPHEMERAL_IDENTITY_SEED] }
                .firstWithTimeout(EPHEMERAL_READ_TIMEOUT_MS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("SimIdentitySeedRepositoryImpl: Failed to read stored ephemeral seed", e)
            null
        }

    private suspend fun executeMigrationSafely(
        ephemeralSeed: String,
        persistentSeed: String,
    ) {
        try {
            performRecoveryMigration(ephemeralSeed, persistentSeed)
            AppLogger.d("Seed recovered from DataStore after ephemeral fallback")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Migration failed - ensure it's kept in storage for next attempt
            try {
                val stored =
                    dataStore.data
                        .map { it[DataStoreKeys.EPHEMERAL_IDENTITY_SEED] }
                        .firstWithTimeout(EPHEMERAL_READ_TIMEOUT_MS)
                if (stored != ephemeralSeed) {
                    dataStore.edit { it[DataStoreKeys.EPHEMERAL_IDENTITY_SEED] = ephemeralSeed }
                }
            } catch (_: Exception) {
            }
            seedMutex.withLock {
                cachedEphemeralSeed = ephemeralSeed
            }
            AppLogger.w("SimIdentitySeedRepositoryImpl: recovery migration failed, will retry", e)
        }
    }

    private suspend fun performRecoveryMigration(
        ephemeralSeed: String,
        persistentSeed: String,
    ) {
        try {
            // Store ephemeral seed as requested ("temporary storage" for migration source record)
            dataStore.edit { it[DataStoreKeys.EPHEMERAL_IDENTITY_SEED] = ephemeralSeed }

            val discoveryResult = discovery.get().discoverSims(persistentSeed)
            if (discoveryResult is SimDiscoveryResult.Available) {
                val outcome =
                    migrationService.get().migrateFromEphemeralSeed(
                        ephemeralSeed = ephemeralSeed,
                        activeSims = discoveryResult.sims,
                    )
                if (!outcome.partialFailure) {
                    dataStore.edit { it.remove(DataStoreKeys.EPHEMERAL_IDENTITY_SEED) }
                    AppLogger.d("SimIdentitySeedRepositoryImpl: Ephemeral data migration completed successfully")
                } else {
                    AppLogger.w("SimIdentitySeedRepositoryImpl: Ephemeral migration finished with partial failure")
                }
            } else {
                AppLogger.w("SimIdentitySeedRepositoryImpl: Migration skipped, SIMs not available")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("SimIdentitySeedRepositoryImpl: Failed to migrate data from ephemeral seed", e)
            throw e // Propagate to keep marker
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("SimIdentitySeedRepositoryImpl: Error accessing seed in DataStore", e)
            null
        }

    private suspend fun DataStore<Preferences>.getOrCreateSeedInternal(generator: () -> String): String {
        var resultSeed: String? = null
        this.edit { preferences ->
            val existing = preferences[DataStoreKeys.SIM_IDENTITY_SEED]
            if (!existing.isNullOrEmpty()) {
                resultSeed = existing
            } else {
                val newSeed = generator()
                preferences[DataStoreKeys.SIM_IDENTITY_SEED] = newSeed
                resultSeed = newSeed
            }
        }
        return resultSeed ?: ""
    }

    private companion object {
        private const val INITIAL_RETRY_DELAY_MS = 200L
        private const val MAX_RETRIES = 2
        private const val EPHEMERAL_READ_TIMEOUT_MS = 100L
    }
    }
