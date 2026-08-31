package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import kotlinx.coroutines.CoroutineScope

object SimIdentityRepositoryTestHelper {
    @Suppress("LongParameterList")
    fun createRepository(
        discoveryDeps: SimDiscoveryDependencies,
        dataStore: DataStore<Preferences>,
        seedRepository: SimIdentitySeedRepository,
        appSettingsRepository: AppSettingsRepository,
        migrationService: IdentityMigrationService,
        mockSimProvider: MockSimProvider,
        timeProvider: TimeProvider,
        scope: CoroutineScope,
    ): SimIdentityRepositoryImpl {
        val discovery = SimIdentityDiscovery(discoveryDeps)
        val persistence = SimIdentityPersistence(dataStore, migrationService)
        val cache = SimIdentityCache(timeProvider)
        val fetcher = ActiveSimsFetcher(discovery, persistence, cache)

        val observer =
            SimIdentityObserver(
                discovery = discovery,
                seedRepository = seedRepository,
                appSettingsRepository = appSettingsRepository,
                mockSimProvider = mockSimProvider,
                fetcher = fetcher,
                scope = scope,
                cache = cache,
                permissionStateNotifier = discoveryDeps.permissionStateNotifier,
            )
        val resolver =
            SimIdentityResolver(
                discovery = discovery,
                persistence = persistence,
                cache = cache,
            )

        return SimIdentityRepositoryImpl(
            observer = observer,
            resolver = resolver,
            persistence = persistence,
        )
    }
}
