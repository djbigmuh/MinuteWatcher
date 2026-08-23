package com.example.minutewatcher.core.data

import android.telephony.SubscriptionInfo
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.data.util.SimIdentityUtils
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
    ) {
        /**
         * Fetches active SIMs with exponential backoff retry logic.
         *
         * @param seed The identity seed result.
         * @return List of active SIM info.
         * @throws IOException if terminal DataStore error occurs.
         */
        suspend fun fetchActiveSimsWithRetry(seed: SeedResult): List<ActiveSimInfo> {
            var retryDelay = Constants.Flow.RETRY_DELAY_MS
            val maxDelay = Constants.Flow.LONG_TIMEOUT_MS * MAX_DELAY_MULTIPLIER
            repeat(MAX_FETCH_RETRIES) { attempt ->
                try {
                    return getActiveSimsInternal(seed)
                } catch (e: Exception) {
                    handleFetchRetryException(e, attempt, retryDelay)
                    retryDelay = (retryDelay * RETRY_DELAY_MULTIPLIER).coerceAtMost(maxDelay)
                }
            }
            throw IOException("Terminal DataStore error after $MAX_FETCH_RETRIES retries")
        }

        @Suppress("ReturnCount")
        private suspend fun getActiveSimsInternal(seed: SeedResult): List<ActiveSimInfo> {
            val seedValue =
                when (seed) {
                    is SeedResult.Real -> seed.value
                    is SeedResult.Fallback -> {
                        AppLogger.e("Cannot fetch active SIMs: identity seed unavailable", null)
                        return emptyList()
                    }
                }

            val infoList = discovery.getActiveSubscriptions()
            val identities = infoList.map { SimIdentityUtils.createIdentityFromInfo(it, seedValue) }

            persistence.retainMigrated(identities.asSequence().map { it.stableId }.toSet())

            if (infoList.isEmpty()) return emptyList()

            try {
                persistence.saveSimIdentitiesInfo(identities)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Failed to save identity info for active SIMs", e)
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
                is IOException -> {
                    if (attempt == (MAX_FETCH_RETRIES - 1)) {
                        throw IOException("Terminal DataStore error after $MAX_FETCH_RETRIES retries", e)
                    }
                    AppLogger.e("ActiveSimsFetcher: DataStore error, retrying", e)
                    delay(delay.milliseconds)
                }
                is CancellationException -> throw e
                is SecurityException -> {
                    AppLogger.e("ActiveSimsFetcher: permission error fetching active SIMs", e)
                    throw e
                }
                else -> {
                    AppLogger.e("Failed to fetch active SIMs", e)
                }
            }
        }

        companion object {
            private const val MAX_DELAY_MULTIPLIER = 3
            private const val MAX_FETCH_RETRIES = 5
            private const val RETRY_DELAY_MULTIPLIER = 2
        }
    }
