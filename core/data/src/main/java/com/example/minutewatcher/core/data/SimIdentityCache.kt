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

        /**
         * Returns cached identity if valid (TTL not expired).
         */
        fun getManual(slot: Int): SimIdentity? {
            val cachedManual = manualResolutionCache[slot] ?: return null
            val (identity, timestamp) = cachedManual
            return if ((timeProvider.elapsedRealtime() - timestamp) < MANUAL_RESOLUTION_CACHE_TTL_MS) {
                identity
            } else {
                manualResolutionCache.remove(slot)
                null
            }
        }

        fun putManual(
            slot: Int,
            identity: SimIdentity,
        ) {
            manualResolutionCache[slot] = identity to timeProvider.elapsedRealtime()
        }

        fun removeManual(slot: Int) {
            manualResolutionCache.remove(slot)
        }

        fun cleanupManual(activeSlots: Set<Int>) {
            manualResolutionCache.keys.removeAll { it in activeSlots }
        }

        companion object {
            private const val MANUAL_RESOLUTION_CACHE_TTL_MS = 60_000L
        }
    }
