package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.data.di.AppDataStore
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SimIdentitySeedRepositoryImpl
    @Inject
    constructor(
        @AppDataStore private val dataStore: DataStore<Preferences>,
    ) : SimIdentitySeedRepository {
        companion object {
            private val SIM_IDENTITY_SEED = stringPreferencesKey("sim_identity_seed")
        }

        private val seedMutex = Mutex()
        private val ephemeralSeed by lazy {
            UUID
                .randomUUID()
                .toString()
        }

        override val seedFlow: Flow<String> =
            flow {
                emitAll(
                    dataStore.data
                        .catch { e ->
                            AppLogger.e("DataStore error in seedFlow, emitting ephemeral seed", e)
                            emit(emptyPreferences())
                        }.map { prefs ->
                            prefs[SIM_IDENTITY_SEED] ?: generateEphemeralSeed()
                        },
                )
            }

        private fun generateEphemeralSeed(): String = ephemeralSeed

        override suspend fun setSeed(seed: String) {
            dataStore.edit { it[SIM_IDENTITY_SEED] = seed }
        }

        /**
         * Returns the existing seed or generates and saves a new one.
         * If the DataStore is corrupted or inaccessible, generates an ephemeral seed
         * that will not be persisted.
         */
        override suspend fun getOrCreateSeed(generator: () -> String): String =
            seedMutex.withLock {
                try {
                    var seed = dataStore.data.first()[SIM_IDENTITY_SEED] ?: ""
                    if (seed.isEmpty()) {
                        seed = generator()
                        dataStore.edit { it[SIM_IDENTITY_SEED] = seed }
                    }
                    seed
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Failed to read/write identity seed, generating ephemeral seed", e)
                    generator() // Ephemeral seed; will be regenerated on next successful access
                }
            }
    }
