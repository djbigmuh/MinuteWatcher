package host.serv.djbigmuh.minutewatcher.core.data

import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import host.serv.djbigmuh.minutewatcher.core.domain.TrackingStatusProvider
import host.serv.djbigmuh.minutewatcher.core.domain.TrackingStatusRepository
import host.serv.djbigmuh.minutewatcher.core.model.TrackingKey
import host.serv.djbigmuh.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [TrackingStatusRepository] that aggregates statuses from multiple [TrackingStatusProvider]s.
 *
 * Safe to call from any coroutine context.
 */
@Singleton
class TrackingStatusRepositoryImpl
    @Inject
    constructor(
        private val statusProviders: Set<@JvmSuppressWildcards TrackingStatusProvider>,
    ) : TrackingStatusRepository {
        /**
         * Triggers recovery for the tracker associated with the given key.
         *
         * Safe to call from any coroutine context.
         * @throws java.io.IOException if recovery fails.
         */
        override suspend fun recoverTracking(key: TrackingKey) {
            val provider = statusProviders.find { it.key == key }
            if (provider != null) {
                provider.recover()
            } else {
                AppLogger.e("TrackingStatusRepository: No provider found for key $key. DI configuration error?")
            }
        }

        /**
         * Returns a flow of a map containing tracking statuses for all providers.
         *
         * Safe to call from any coroutine context.
         */
        override fun observeTrackingStatus(): Flow<Map<TrackingKey, TrackingStatus>> {
            if (statusProviders.isEmpty()) {
                return flowOf(emptyMap())
            }
            val flows =
                statusProviders.map { provider ->
                    provider.status.map { status -> provider.key to status }
                }
            return combine(flows) { pairs ->
                pairs.toMap()
            }
        }
    }
