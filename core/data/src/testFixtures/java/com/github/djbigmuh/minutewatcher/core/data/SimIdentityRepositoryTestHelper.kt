package com.github.djbigmuh.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.github.djbigmuh.minutewatcher.core.common.util.TimeProvider
import com.github.djbigmuh.minutewatcher.core.data.testing.FakeAppSettingsRepository
import com.github.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import com.github.djbigmuh.minutewatcher.core.domain.SimIdentitySeedRepository
import kotlinx.coroutines.CoroutineScope

object SimIdentityRepositoryTestHelper {
    fun createRepository(
        discoveryDeps: SimDiscoveryDependencies,
        dataStore: DataStore<Preferences>,
        seedRepository: SimIdentitySeedRepository,
        appSettingsRepository: AppSettingsRepository = FakeAppSettingsRepository(),
        migrationService: IdentityMigrationService,
        mockSimProvider: Any? = null,
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
                fetcher = fetcher,
                migrationService = migrationService,
                scope = scope,
                cache = cache,
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
