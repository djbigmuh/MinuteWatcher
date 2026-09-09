package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.model.SimIdentity
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory cache for SIM identities and manual resolution results.
 *
 * Safe to call from any coroutine context.
 */
@Singleton
class SimIdentityCache
    @Inject
    constructor(
        private val timeProvider: TimeProvider,
    ) {
        private val manualResolutionCache = ConcurrentHashMap<Int, Pair<SimIdentity, Long>>()
        private val canonicalIdentities = ConcurrentHashMap<String, SimIdentity>()

        /**
         * Returns a canonical instance of the given identity.
         * Ensures referential equality for identical SIMs across the module.
         */
        fun getOrCreateCanonical(identity: SimIdentity): SimIdentity =
            canonicalIdentities.putIfAbsent(identity.stableId, identity) ?: identity

        /**
         * Returns cached identity if valid (TTL not expired).
         * Uses atomic compute to prevent race conditions during TTL check and removal.
         */
        fun getManual(slot: Int): SimIdentity? {
            val now = timeProvider.elapsedRealtime()
            var result: SimIdentity? = null
            manualResolutionCache.compute(slot) { _, value ->
                if (value == null) return@compute null
                val (identity, timestamp) = value
                if ((now - timestamp) < MANUAL_RESOLUTION_CACHE_TTL_MS) {
                    result = identity
                    value // Keep entry
                } else {
                    null // Remove entry
                }
            }
            return result
        }

        fun putManual(
            slot: Int,
            identity: SimIdentity,
        ) {
            val canonical = getOrCreateCanonical(identity)
            manualResolutionCache[slot] = canonical to timeProvider.elapsedRealtime()
        }

        fun removeManual(slot: Int) {
            manualResolutionCache.remove(slot)
        }

        fun cleanupManual(activeSlots: Set<Int>) {
            manualResolutionCache.keys.removeAll { it !in activeSlots }
            // Optional: periodically cleanup canonicalIdentities if memory is a concern,
            // but for few SIM cards it's fine to keep them.
        }

        companion object {
            private const val MANUAL_RESOLUTION_CACHE_TTL_MS = 60_000L
        }
    }
