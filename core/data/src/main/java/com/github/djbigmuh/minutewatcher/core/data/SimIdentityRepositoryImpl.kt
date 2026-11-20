package com.github.djbigmuh.minutewatcher.core.data

import androidx.annotation.VisibleForTesting
import com.github.djbigmuh.minutewatcher.core.domain.SimIdentityRepository
import com.github.djbigmuh.minutewatcher.core.model.ActiveSimInfo
import com.github.djbigmuh.minutewatcher.core.model.SimIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

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
 *
 * NOTE: This repository is the main gateway for SIM management,
 * exposing a rich set of observer and resolution APIs used by data and feature layers.
 */
@Singleton
class SimIdentityRepositoryImpl
    @Inject
    internal constructor(
        @get:VisibleForTesting
        internal val observer: SimIdentityObserver,
        private val resolver: SimIdentityResolver,
        private val persistence: SimIdentityPersistence,
    ) : SimIdentityRepository {
        private val activeSimsMutex = Mutex()

        override suspend fun getIdentity(slot: Int): String = getSimIdentity(slot).stableId

        override suspend fun getSimIdentity(slot: Int): SimIdentity =
            resolver.getSimIdentity(
                slot = slot,
                activeSimsCache = observer.activeSimsCache,
                seedFlow = observer.seedFlow,
                activeSimsMutex = activeSimsMutex,
            )

        override fun observeActiveSims(): Flow<List<ActiveSimInfo>> = observer.activeSimsCache

        override fun observeResolvedActiveSims(): StateFlow<List<ActiveSimInfo>?> = observer.resolvedActiveSimsCache

        override fun getActiveIdentities(): Flow<List<String>> =
            observeActiveSims().map { sims -> sims.map { it.identity.stableId } }.distinctUntilChanged()

        override suspend fun setSimOperator(
            identity: String,
            name: String,
            brandKey: String?,
        ) = persistence.setSimOperator(identity, name, brandKey)
    }
