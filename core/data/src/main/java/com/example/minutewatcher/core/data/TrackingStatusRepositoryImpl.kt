package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.domain.TrackingStatusRepository
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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
        override suspend fun recoverTracking(key: String) {
            statusProviders.find { it.key == key }?.recover()
        }

        /**
         * Returns a flow of a map containing tracking statuses for all providers.
         *
         * Safe to call from any coroutine context.
         */
        override fun observeTrackingStatus(): Flow<Map<String, TrackingStatus>> {
            val flows =
                statusProviders.map { provider ->
                    provider.status.map { status -> provider.key to status }
                }
            return combine(flows) { pairs ->
                pairs.toMap()
            }
        }
    }
