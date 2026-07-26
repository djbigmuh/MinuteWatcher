package com.example.minutewatcher.core.domain

import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.flow.Flow

interface TrackingStatusProvider {
    val key: String
    val status: Flow<TrackingStatus>
}
