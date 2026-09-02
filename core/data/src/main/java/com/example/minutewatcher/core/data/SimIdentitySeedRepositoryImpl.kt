package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.minutewatcher.core.common.di.AppDataStore
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.data.di.DataStoreKeys
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.SimDiscoveryResult
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
        override suspend fun getOrCreateSeed(generator: () -> String): String {
            var ephemeralToMigrate: String? = null
            val firstAttempt =
                seedMutex.withLock {
                    val seed = tryGetPersistentSeed(generator)
                    if (seed != null && (_isEphemeralSeedActive.value || cachedEphemeralSeed != null)) {
                        ephemeralToMigrate = cachedEphemeralSeed
                        _isEphemeralSeedActive.value = false
                        cachedEphemeralSeed = null
                    }
                    seed
                }

            if (firstAttempt != null) {
                ephemeralToMigrate?.let { executeMigrationSafely(it, firstAttempt) }
                return firstAttempt
            }

            AppLogger.w("SimIdentitySeedRepositoryImpl: Persistent seed access failed, retrying in 500ms...")
            kotlinx.coroutines.delay(RETRY_DELAY_MS.milliseconds)

            var ephemeralToMigrateRetry: String? = null
            val secondAttempt =
                seedMutex.withLock {
                    val seed = tryGetPersistentSeed(generator)
                    if (seed != null) {
                        if (_isEphemeralSeedActive.value || cachedEphemeralSeed != null) {
                            ephemeralToMigrateRetry = cachedEphemeralSeed
                            _isEphemeralSeedActive.value = false
                            cachedEphemeralSeed = null
                        }
                        seed
                    } else {
                        AppLogger.e(
                            "CRITICAL: DataStore unavailable after retry, using ephemeral seed. " +
                                "All SIM identities will change. Previous statistics will be orphaned.",
                        )
                        _isEphemeralSeedActive.value = true
                        cachedEphemeralSeed ?: generator().also { cachedEphemeralSeed = it }
                    }
                }

            ephemeralToMigrateRetry?.let { executeMigrationSafely(it, secondAttempt) }
            return secondAttempt
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
            // Миграция не удалась — оставляем хэш, повторим при следующем вызове
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
                migrationService.get().migrateFromEphemeralSeed(
                    ephemeralSeed = ephemeralSeed,
                    activeSims = discoveryResult.sims,
                )
                AppLogger.d("SimIdentitySeedRepositoryImpl: Ephemeral data migration completed successfully")
            } else {
                AppLogger.w("SimIdentitySeedRepositoryImpl: Migration skipped, SIMs not available")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("SimIdentitySeedRepositoryImpl: Failed to migrate data from ephemeral seed", e)
            throw e // Пробрасываем, чтобы не сбрасывать флаг
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
        } catch (e: Exception) {
            AppLogger.w("SimIdentitySeedRepositoryImpl: Error accessing seed in DataStore", e)
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
