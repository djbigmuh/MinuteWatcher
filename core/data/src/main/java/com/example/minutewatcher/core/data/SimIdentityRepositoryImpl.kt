package com.example.minutewatcher.core.data

import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.SimIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * Implementation of [SimIdentityRepository].
 *
 * Central hub for managing SIM identities, discovery, and persistence.
 * Delegates work to specialized components:
 * - [SimIdentityObserver] for observing active SIM changes and seed management.
 * - [SimIdentityResolver] for resolving specific slot identities with caching.
 * - [SimIdentityPersistence] for DataStore operations.
 *
 * Safe to call from any coroutine context.
 */
@Suppress("TooManyFunctions")
@Singleton
class SimIdentityRepositoryImpl
    @Inject
    internal constructor(
        private val observer: SimIdentityObserver,
        private val resolver: SimIdentityResolver,
        private val persistence: SimIdentityPersistence,
    ) : SimIdentityRepository {
        private val activeSimsMutex = Mutex()

        @VisibleForTesting
        @Suppress("unused") // Used in tests
        constructor(
            discoveryDeps: SimDiscoveryDependencies,
            dataStore: DataStore<Preferences>,
            seedRepository: SimIdentitySeedRepository,
            appSettingsRepository: AppSettingsRepository,
            migrationService: IdentityMigrationService,
            mockSimProvider: MockSimProvider,
            timeProvider: TimeProvider,
            scope: CoroutineScope,
        ) : this(
            createComponents(
                discoveryDeps,
                dataStore,
                seedRepository,
                appSettingsRepository,
                migrationService,
                mockSimProvider,
                timeProvider,
                scope,
            ),
        )

        private constructor(components: SimIdentityComponents) : this(
            components.observer,
            components.resolver,
            components.persistence,
        )

        override suspend fun getIdentity(slot: Int): String = getSimIdentity(slot).stableId

        override suspend fun getSimIdentity(slot: Int): SimIdentity =
            resolver.getSimIdentity(
                slot = slot,
                activeSimsCache = observer.activeSimsCache,
                seedFlow = observer.seedFlow,
                activeSimsMutex = activeSimsMutex,
            )

        override fun observeActiveSims(): Flow<List<ActiveSimInfo>> = observer.activeSimsCache

        override fun getActiveIdentities(): Flow<List<String>> =
            observeActiveSims().map { sims -> sims.map { it.identity.stableId } }.distinctUntilChanged()

        @OptIn(FlowPreview::class)
        override fun observeIdentities(): Flow<List<String>> =
            getActiveIdentities().debounce(Constants.Flow.DEBOUNCE_MS.milliseconds)

        override fun getSimOperator(identity: String): Flow<String> = persistence.getSimOperator(identity)

        override fun getCustomSimOperator(identity: String): Flow<String?> = persistence.getCustomSimOperator(identity)

        override suspend fun setSimOperator(
            identity: String,
            name: String,
        ) = persistence.setSimOperator(identity, name)

        override suspend fun setCustomSimOperator(
            identity: String,
            name: String?,
        ) = persistence.setCustomSimOperator(identity, name)

        override fun getSimCode(identity: String): Flow<String> = persistence.getSimCode(identity)

        override suspend fun setSimCode(
            identity: String,
            code: String,
        ) = persistence.setSimCode(identity, code)

        private data class SimIdentityComponents(
            val observer: SimIdentityObserver,
            val resolver: SimIdentityResolver,
            val persistence: SimIdentityPersistence,
        )

        companion object {
            @Suppress("LongParameterList")
            private fun createComponents(
                discoveryDeps: SimDiscoveryDependencies,
                dataStore: DataStore<Preferences>,
                seedRepository: SimIdentitySeedRepository,
                appSettingsRepository: AppSettingsRepository,
                migrationService: IdentityMigrationService,
                mockSimProvider: MockSimProvider,
                timeProvider: TimeProvider,
                scope: CoroutineScope,
            ): SimIdentityComponents {
                val discovery = SimIdentityDiscovery(discoveryDeps)
                val persistence = SimIdentityPersistence(dataStore, migrationService)
                val cache = SimIdentityCache(timeProvider)
                val fetcher = ActiveSimsFetcher(discovery, persistence)

                return SimIdentityComponents(
                    observer =
                        SimIdentityObserver(
                            discovery = discovery,
                            seedRepository = seedRepository,
                            appSettingsRepository = appSettingsRepository,
                            mockSimProvider = mockSimProvider,
                            fetcher = fetcher,
                            scope = scope,
                            cache = cache,
                        ),
                    resolver =
                        SimIdentityResolver(
                            discovery = discovery,
                            persistence = persistence,
                            cache = cache,
                        ),
                    persistence = persistence,
                )
            }
        }
    }
