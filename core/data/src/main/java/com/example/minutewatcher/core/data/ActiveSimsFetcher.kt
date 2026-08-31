package com.example.minutewatcher.core.data

import android.telephony.SubscriptionInfo
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.data.util.SimIdentityUtils
import com.example.minutewatcher.core.domain.SeedUnavailableException
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.SimIdentity
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
         * Retries only transient storage errors (IOException) and unavailable identity seeds.
         * Programming errors (like NPE) are rethrown immediately without retry.
         *
         * @param seed The identity seed result.
         * @return List of active SIM info.
         * @throws IOException if terminal storage error occurs.
         */
        suspend fun fetchActiveSimsWithRetry(seed: SeedResult): List<ActiveSimInfo> {
            var retryDelay = Constants.Flow.RETRY_DELAY_MS
            val maxDelay = Constants.Flow.LONG_TIMEOUT_MS * MAX_DELAY_MULTIPLIER
            repeat(MAX_FETCH_ATTEMPTS + 1) { attempt ->
                try {
                    return getActiveSimsInternal(seed)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SecurityException) {
                    handleFetchRetryException(e, attempt, retryDelay)
                } catch (e: SeedUnavailableException) {
                    handleFetchRetryException(e, attempt, retryDelay)
                    retryDelay = (retryDelay * RETRY_DELAY_MULTIPLIER).coerceAtMost(maxDelay)
                } catch (e: IOException) {
                    handleFetchRetryException(e, attempt, retryDelay)
                    retryDelay = (retryDelay * RETRY_DELAY_MULTIPLIER).coerceAtMost(maxDelay)
                }
            }
            throw IOException("Terminal DataStore error after ${MAX_FETCH_ATTEMPTS + 1} attempts")
        }

        @Suppress("ReturnCount", "ThrowsCount")
        private suspend fun getActiveSimsInternal(seed: SeedResult): List<ActiveSimInfo> {
            val seedValue =
                when (seed) {
                    is SeedResult.Real -> seed.value
                    is SeedResult.Fallback -> {
                        throw SeedUnavailableException()
                    }
                }

            val infoList = discovery.getActiveSubscriptions()
            val identities =
                infoList.map { info ->
                    val identity = SimIdentityUtils.createIdentityFromInfo(info, seedValue)
                    cache.getOrCreateCanonical(identity)
                }

            persistence.retainMigrated(identities.asSequence().map { it.stableId }.toSet())

            if (infoList.isEmpty()) return emptyList()

            try {
                persistence.saveSimIdentitiesInfo(identities)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("ActiveSimsFetcher: non-terminal error saving identities, continuing", e)
            }

            return createActiveSimInfoList(infoList, identities)
        }

        private fun createActiveSimInfoList(
            infoList: List<SubscriptionInfo>,
            identities: List<SimIdentity>,
        ): List<ActiveSimInfo> {
            val defaultDataSubId = discovery.getDefaultDataSubscriptionId()
            val defaultVoiceSubId = discovery.getDefaultVoiceSubscriptionId()

            return infoList.zip(identities).map { (info, identity) ->
                ActiveSimInfo(
                    identity = identity,
                    slotIndex = info.simSlotIndex,
                    subscriptionId = info.subscriptionId,
                    displayName = info.displayName?.toString(),
                    carrierName = info.carrierName?.toString(),
                    mccMnc = SimIdentityUtils.getMccMnc(info),
                    isDefaultData = info.subscriptionId == defaultDataSubId,
                    isDefaultVoice = info.subscriptionId == defaultVoiceSubId,
                )
            }
        }

        @Suppress("ThrowsCount")
        private suspend fun handleFetchRetryException(
            e: Exception,
            attempt: Int,
            delay: Long,
        ) {
            when (e) {
                is SeedUnavailableException -> {
                    if (attempt == MAX_FETCH_ATTEMPTS) throw e
                    AppLogger.w("ActiveSimsFetcher: Seed unavailable, retrying fetch (attempt ${attempt + 1})")
                    delay(delay.milliseconds)
                }
                is IOException -> {
                    if (attempt == MAX_FETCH_ATTEMPTS) {
                        throw IOException("Terminal DataStore error after ${MAX_FETCH_ATTEMPTS + 1} attempts", e)
                    }
                    AppLogger.e("ActiveSimsFetcher: DataStore error, retrying", e)
                    delay(delay.milliseconds)
                }
                is SecurityException -> {
                    AppLogger.e("ActiveSimsFetcher: permission error fetching active SIMs", e)
                    throw e
                }
                else -> throw e
            }
        }

        companion object {
            private const val MAX_DELAY_MULTIPLIER = 3

            /** Количество повторных попыток (не считая исходной). Итого попыток = значение + 1 */
            private const val MAX_FETCH_ATTEMPTS = 4

            private const val RETRY_DELAY_MULTIPLIER = 2
        }
    }
