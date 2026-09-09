package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.minutewatcher.core.common.di.AppDataStore
import com.example.minutewatcher.core.data.di.DataStoreKeys
import com.example.minutewatcher.core.domain.MigrationFlagsRepository
import com.example.minutewatcher.core.model.MigrationSkipReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [MigrationFlagsRepository] using [DataStore].
 *
 * Tracks the completion status of various data migrations.
 * AOSP DataStore compliant.
 * Safe to call from any coroutine context.
 */
@Singleton
class MigrationFlagsRepositoryImpl
    @Inject
    constructor(
        @AppDataStore dataStore: DataStore<Preferences>,
    ) : BaseDataStoreRepository(dataStore),
        MigrationFlagsRepository {
        // Ключи DataStore — деталь реализации. Публичный API репозитория не должен их раскрывать.
        // Это соответствует принципу Dependency Inversion.

        /**
         * Returns a flow indicating if V1 migration is completed.
         *
         * Safe to call from any coroutine context.
         */
        override fun isMigrationV1Completed(): Flow<Boolean> =
            getData(DataStoreKeys.MIGRATION_V1_COMPLETED, defaultValue = false)

        /**
         * Updates the V1 migration completion status.
         *
         * Safe to call from any coroutine context.
         * @throws java.io.IOException if write fails.
         */
        override suspend fun setMigrationV1Completed(completed: Boolean) {
            dataStore.edit { it[DataStoreKeys.MIGRATION_V1_COMPLETED] = completed }
        }

        /**
         * Returns a flow indicating if identity stability migration is completed.
         *
         * Safe to call from any coroutine context.
         */
        override fun isMigrationIdentityStabilityCompleted(): Flow<Boolean> =
            getData(DataStoreKeys.MIGRATION_IDENTITY_STABILITY_COMPLETED, defaultValue = false)

        /**
         * Updates the identity stability migration completion status.
         *
         * Safe to call from any coroutine context.
         * @throws java.io.IOException if write fails.
         */
        override suspend fun setMigrationIdentityStabilityCompleted(completed: Boolean) {
            dataStore.edit { it[DataStoreKeys.MIGRATION_IDENTITY_STABILITY_COMPLETED] = completed }
        }

        /**
         * Returns a flow of the reason why V1 migration was skipped, if any.
         *
         * Safe to call from any coroutine context.
         */
        override fun getMigrationV1SkippedReason(): Flow<String?> =
            dataStore.data.map {
                it[DataStoreKeys.MIGRATION_V1_SKIPPED_REASON]
            }

        /**
         * Records the reason for skipping V1 migration.
         *
         * Safe to call from any coroutine context.
         * @throws java.io.IOException if write fails.
         */
        override suspend fun setMigrationV1Skipped(reason: MigrationSkipReason) {
            dataStore.edit { it[DataStoreKeys.MIGRATION_V1_SKIPPED_REASON] = reason.name }
        }

        override fun isInitialSetupCompleted(): Flow<Boolean> =
            getData(DataStoreKeys.INITIAL_SETUP_COMPLETED, defaultValue = false)

        override suspend fun setInitialSetupCompleted(completed: Boolean) {
            dataStore.edit { it[DataStoreKeys.INITIAL_SETUP_COMPLETED] = completed }
        }
    }
