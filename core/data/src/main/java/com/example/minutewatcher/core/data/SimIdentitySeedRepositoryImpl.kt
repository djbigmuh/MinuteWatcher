package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.minutewatcher.core.data.di.AppDataStore
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

        override fun getSeed(): Flow<String> = dataStore.data.map { it[SIM_IDENTITY_SEED] ?: "" }

        override suspend fun setSeed(seed: String) {
            dataStore.edit { it[SIM_IDENTITY_SEED] = seed }
        }

        override suspend fun getOrCreateSeed(generator: () -> String): String =
            seedMutex.withLock {
                var seed = dataStore.data.first()[SIM_IDENTITY_SEED] ?: ""
                if (seed.isEmpty()) {
                    seed = generator()
                    dataStore.edit { it[SIM_IDENTITY_SEED] = seed }
                }
                seed
            }
    }
