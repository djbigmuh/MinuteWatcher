package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.domain.TrackingStatusRepository
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrackingStatusRepositoryImpl
    @Inject
    constructor(
        private val statusProviders: Set<@JvmSuppressWildcards TrackingStatusProvider>,
    ) : TrackingStatusRepository {
        override suspend fun recoverTracking(key: String) {
            statusProviders.find { it.key == key }?.recover()
        }

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
