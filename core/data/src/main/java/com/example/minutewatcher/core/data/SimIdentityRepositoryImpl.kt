package com.example.minutewatcher.core.data

import androidx.annotation.VisibleForTesting
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.SimIdentity
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
 *
 * NOTE: Suppressing TooManyFunctions as this repository is the main gateway for SIM management,
 * exposing a rich set of observer and resolution APIs used by data and feature layers.
 */
@Suppress("TooManyFunctions")
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
    }
