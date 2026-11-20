package com.github.djbigmuh.minutewatcher.core.data

import com.github.djbigmuh.minutewatcher.core.common.util.AppLogger
import com.github.djbigmuh.minutewatcher.core.model.Constants
import com.github.djbigmuh.minutewatcher.core.model.SimDiscoveryFailureReason
import com.github.djbigmuh.minutewatcher.core.model.SimDiscoveryResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * Handles fetching active SIMs with retry logic and DataStore persistence.
 *
 * Safe to call from any coroutine context.
 */
@Singleton
internal class ActiveSimsFetcher
    @Inject
    constructor(
        private val discovery: SimIdentityDiscovery,
        private val persistence: SimIdentityPersistence,
        private val cache: SimIdentityCache,
    ) {
        /**
         * Fetches active SIMs with exponential backoff retry logic.
         *
         * [IOException] occurring during persistence operations are retried with
         * exponential delay. After retry attempts are exhausted, the exception is caught and
         * converted to [SimDiscoveryResult.Failed] with [SimDiscoveryFailureReason.STORAGE_ERROR].
         *
         * [CancellationException] is always rethrown.
         *
         * @param seed The identity seed result.
         * @return Discovery result containing list of active SIM info or [SimDiscoveryResult.Failed]
         * with [SimDiscoveryFailureReason.STORAGE_ERROR] on terminal storage error.
         */
        suspend fun fetchActiveSimsWithRetry(seed: SeedResult): SimDiscoveryResult {
            var retryDelay = Constants.Flow.RETRY_DELAY_MS
            val maxDelay = Constants.Flow.LONG_TIMEOUT_MS * MAX_DELAY_MULTIPLIER
            repeat(MAX_FETCH_ATTEMPTS + 1) { attempt ->
                try {
                    return getActiveSimsInternal(seed)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    if (attempt < MAX_FETCH_ATTEMPTS) {
                        AppLogger.e("ActiveSimsFetcher: DataStore error, retrying", e)
                        delay(retryDelay.milliseconds)
                        retryDelay = (retryDelay * RETRY_DELAY_MULTIPLIER).coerceAtMost(maxDelay)
                    } else {
                        AppLogger.e("ActiveSimsFetcher: Terminal DataStore error after $attempt retries", e)
                    }
                }
            }
            return SimDiscoveryResult.Failed(SimDiscoveryFailureReason.STORAGE_ERROR)
        }

        private suspend fun getActiveSimsInternal(seed: SeedResult): SimDiscoveryResult {
            val (seedValue, isStable) =
                when (seed) {
                    is SeedResult.Real -> seed.value to seed.isStable
                    is SeedResult.Fallback -> seed.seed to false
                }

            val discoveryResult = discovery.discoverSims(seedValue, isStable)
            if (discoveryResult !is SimDiscoveryResult.Available) return discoveryResult

            val infoList = discoveryResult.sims

            val identities =
                infoList.map { simInfo ->
                    cache.getOrCreateCanonical(simInfo.identity)
                }

            if (!isStable) {
                AppLogger.w("ActiveSimsFetcher: ephemeral identities not persisted")
                return SimDiscoveryResult.Available(infoList)
            }

            persistence.retainMigrated(identities.asSequence().map { it.stableId }.toSet())

            @Suppress("TooGenericExceptionCaught")
            try {
                persistence.saveSimIdentitiesInfo(identities)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Operation must complete without propagating exception for active SIMs fetcher stability.
                AppLogger.e("ActiveSimsFetcher: non-terminal error saving identities, continuing", e)
            }

            return SimDiscoveryResult.Available(infoList)
        }

        companion object {
            private const val MAX_DELAY_MULTIPLIER = 3

            /** Number of retry attempts (excluding the initial attempt). Total attempts = value + 1 */
            private const val MAX_FETCH_ATTEMPTS = 4

            private const val RETRY_DELAY_MULTIPLIER = 2
        }
    }
