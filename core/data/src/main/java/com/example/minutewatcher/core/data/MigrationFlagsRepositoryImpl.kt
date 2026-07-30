package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.minutewatcher.core.data.di.AppDataStore
import com.example.minutewatcher.core.domain.MigrationFlagsRepository
import com.example.minutewatcher.core.model.MigrationSkipReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MigrationFlagsRepositoryImpl @Inject constructor(
    @AppDataStore private val dataStore: DataStore<Preferences>,
) : MigrationFlagsRepository {

    private fun <T> get(
        key: Preferences.Key<T>,
        defaultValue: T,
    ): Flow<T> = dataStore.data.map { it[key] ?: defaultValue }

    override fun isMigrationV1Completed(): Flow<Boolean> = get(SettingsKeys.MIGRATION_V1_COMPLETED, false)

    override suspend fun setMigrationV1Completed(completed: Boolean) {
        dataStore.edit { it[SettingsKeys.MIGRATION_V1_COMPLETED] = completed }
    }

    override fun isMigrationIdentityStabilityCompleted(): Flow<Boolean> =
        get(SettingsKeys.MIGRATION_IDENTITY_STABILITY_COMPLETED, false)

    override suspend fun setMigrationIdentityStabilityCompleted(completed: Boolean) {
        dataStore.edit { it[SettingsKeys.MIGRATION_IDENTITY_STABILITY_COMPLETED] = completed }
    }

    override fun getMigrationV1SkippedReason(): Flow<String?> = dataStore.data.map { it[SettingsKeys.MIGRATION_V1_SKIPPED_REASON] }

    override suspend fun setMigrationV1Skipped(reason: MigrationSkipReason) {
        dataStore.edit { it[SettingsKeys.MIGRATION_V1_SKIPPED_REASON] = reason.name }
    }
}
