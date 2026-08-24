package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.data.util.SimIdentityUtils
import com.example.minutewatcher.core.domain.SeedUnavailableException
import com.example.minutewatcher.core.domain.SimNotFoundException
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.Constants.SimConstants
import com.example.minutewatcher.core.model.SimIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

@Singleton
internal class SimIdentityResolver
    @Inject
    constructor(
        private val discovery: SimIdentityDiscovery,
        private val persistence: SimIdentityPersistence,
        private val cache: SimIdentityCache,
    ) {
        suspend fun getSimIdentity(
            slot: Int,
            activeSimsCache: StateFlow<List<ActiveSimInfo>>,
            seedFlow: Flow<SeedResult>,
            activeSimsMutex: Mutex,
        ): SimIdentity {
            require(slot in SimConstants.SUPPORTED_SLOT_INDICES) { "Invalid slot $slot" }

            // 1. Initial fast check under lock
            activeSimsMutex.withLock {
                activeSimsCache.value.find { it.slotIndex == slot }?.let {
                    cache.removeManual(slot)
                    return it.identity
                }
                cache.getManual(slot)?.let { return it }
            }

            // 2. Perform slow operations outside the lock
            val identity =
                withTimeoutOrNull(Constants.Flow.LONG_TIMEOUT_MS.milliseconds) {
                    val seed =
                        withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                            seedFlow.firstOrNull()
                        } ?: SeedResult.Fallback

                    val seedValue =
                        when (seed) {
                            is SeedResult.Real -> seed.value
                            is SeedResult.Fallback -> throw SeedUnavailableException(slot)
                        }

                    discovery.getActiveSubscriptions().find { it.simSlotIndex == slot }?.let {
                        SimIdentityUtils.createIdentityFromInfo(it, seedValue)
                    } ?: throw SimNotFoundException(slot)
                } ?: throw IOException("Timeout resolving identity for slot $slot")

            // 3. Update cache and persistence under lock
            return activeSimsMutex.withLock {
                // Re-check if someone else resolved it while we were busy
                activeSimsCache.value.find { it.slotIndex == slot }?.let {
                    cache.removeManual(slot)
                    return@withLock it.identity
                }
                cache.getManual(slot)?.let { return@withLock it }

                cache.putManual(slot, identity)
                persistence.saveSimIdentitiesInfo(listOf(identity))
                identity
            }
        }
    }
