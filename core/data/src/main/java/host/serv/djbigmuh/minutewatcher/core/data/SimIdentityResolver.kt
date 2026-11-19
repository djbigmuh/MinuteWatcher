package host.serv.djbigmuh.minutewatcher.core.data

import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import host.serv.djbigmuh.minutewatcher.core.domain.InvalidSlotException
import host.serv.djbigmuh.minutewatcher.core.domain.SeedUnavailableException
import host.serv.djbigmuh.minutewatcher.core.domain.SimNotFoundException
import host.serv.djbigmuh.minutewatcher.core.model.ActiveSimInfo
import host.serv.djbigmuh.minutewatcher.core.model.Constants
import host.serv.djbigmuh.minutewatcher.core.model.Constants.SimConstants
import host.serv.djbigmuh.minutewatcher.core.model.SimDiscoveryResult
import host.serv.djbigmuh.minutewatcher.core.model.SimIdentity
import kotlinx.coroutines.CancellationException
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
        /**
         * Resolves the identity for the specified SIM slot.
         *
         * @throws InvalidSlotException if the slot index is invalid.
         * @throws SimNotFoundException if no SIM is in the slot.
         * @throws SeedUnavailableException if identity seed is unavailable within timeout.
         * @throws IOException if DataStore access fails during persistence.
         */
        suspend fun getSimIdentity(
            slot: Int,
            activeSimsCache: StateFlow<List<ActiveSimInfo>>,
            seedFlow: Flow<SeedResult>,
            activeSimsMutex: Mutex,
        ): SimIdentity {
            validateSlot(slot)

            // 1. Fast atomic check without mutex
            activeSimsCache.value.find { it.slotIndex == slot }?.let {
                cache.removeManual(slot)
                return it.identity
            }
            cache.getManual(slot)?.let { return it }

            return activeSimsMutex.withLock {
                // Double-check cache inside lock to prevent redundant resolution
                activeSimsCache.value.find { it.slotIndex == slot }?.let {
                    cache.removeManual(slot)
                    return@withLock it.identity
                }
                cache.getManual(slot)?.let { return@withLock it }

                // 2. Resolve
                val seed = resolveSeedInternal(seedFlow) ?: throw SeedUnavailableException(slot)
                val (seedValue, isStable) = seed
                val resolvedIdentity = resolveIdentityInternal(slot, seedValue, isStable)
                val identity = cache.getOrCreateCanonical(resolvedIdentity)

                // 3. Update cache and persist
                cache.putManual(slot, identity)
                if (isStable) {
                    tryPersistIdentity(slot, identity)
                }

                identity
            }
        }

        private fun validateSlot(slot: Int) {
            if (slot !in SimConstants.SUPPORTED_SLOT_INDICES) {
                throw InvalidSlotException(slot)
            }
        }

        private suspend fun resolveSeedInternal(seedFlow: Flow<SeedResult>): Pair<String, Boolean>? =
            when (
                val seed =
                    withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                        seedFlow.firstOrNull()
                    }
            ) {
                is SeedResult.Real -> seed.value to seed.isStable
                is SeedResult.Fallback -> seed.seed to false
                null -> null
            }

        private suspend fun resolveIdentityInternal(
            slot: Int,
            seedValue: String,
            isStable: Boolean,
        ): SimIdentity {
            val result =
                withTimeoutOrNull(Constants.Flow.LONG_TIMEOUT_MS.milliseconds) {
                    discovery.discoverSims(seedValue, isStable)
                } ?: throw IOException("Timeout resolving identity for slot $slot")

            if (result is SimDiscoveryResult.Available) {
                val sim = result.sims.find { it.slotIndex == slot }
                if (sim != null) return sim.identity
            }

            throw mapDiscoveryFailureToException(result, slot)
        }

        private fun mapDiscoveryFailureToException(
            result: SimDiscoveryResult,
            slot: Int,
        ): Exception =
            when (result) {
                is SimDiscoveryResult.PermissionMissing ->
                    SecurityException("Permission missing for SIM discovery in slot $slot")

                is SimDiscoveryResult.Unsupported ->
                    UnsupportedOperationException("SIM discovery unsupported for slot $slot")

                is SimDiscoveryResult.Failed ->
                    IOException("SIM discovery failed for slot $slot: ${result.reason}")

                else ->
                    SimNotFoundException(slot)
            }

        private suspend fun tryPersistIdentity(
            slot: Int,
            identity: SimIdentity,
        ) {
            @Suppress("TooGenericExceptionCaught")
            try {
                persistIdentity(slot, identity)
            } catch (e: Exception) {
                cache.removeManual(slot)
                if (e is CancellationException) throw e
                throw e
            }
        }

        private suspend fun persistIdentity(
            slot: Int,
            identity: SimIdentity,
        ) {
            @Suppress("TooGenericExceptionCaught")
            val error: Exception? =
                try {
                    val saved =
                        withTimeoutOrNull(Constants.Flow.LONG_TIMEOUT_MS.milliseconds) {
                            persistence.saveSimIdentitiesInfo(listOf(identity))
                            true
                        } ?: false
                    if (!saved) IOException("Timeout saving SIM identity for slot $slot") else null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("SimIdentityResolver: failed to persist identity for slot $slot", e)
                    if (e is IOException) e else IOException("Failed to save SIM identity for slot $slot", e)
                }
            if (error != null) {
                throw error
            }
        }
    }
