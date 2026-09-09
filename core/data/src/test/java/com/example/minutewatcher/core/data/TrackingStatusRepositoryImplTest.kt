package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.model.TrackingKey
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class TrackingStatusRepositoryImplTest {
    @Test
    fun `recoverTracking calls recover on correct provider`() =
        runTest {
            val provider1 = mock<TrackingStatusProvider>()
            whenever(provider1.key).thenReturn(TrackingKey.TRAFFIC)

            val provider2 = mock<TrackingStatusProvider>()
            whenever(provider2.key).thenReturn(TrackingKey.CALLS)

            val repository = TrackingStatusRepositoryImpl(setOf(provider1, provider2))

            repository.recoverTracking(TrackingKey.TRAFFIC)

            verify(provider1).recover()
        }

    @Test
    fun `observeTrackingStatus aggregates all provider statuses`() =
        runTest {
            val provider1 = mock<TrackingStatusProvider>()
            whenever(provider1.key).thenReturn(TrackingKey.TRAFFIC)
            whenever(provider1.status).thenReturn(MutableStateFlow(TrackingStatus.Active))

            val provider2 = mock<TrackingStatusProvider>()
            whenever(provider2.key).thenReturn(TrackingKey.CALLS)
            whenever(provider2.status).thenReturn(MutableStateFlow(TrackingStatus.Idle))

            val repository = TrackingStatusRepositoryImpl(setOf(provider1, provider2))

            val statusMap = repository.observeTrackingStatus().first()

            assertEquals(2, statusMap.size)
            assertEquals(TrackingStatus.Active, statusMap[TrackingKey.TRAFFIC])
            assertEquals(TrackingStatus.Idle, statusMap[TrackingKey.CALLS])
        }
}
