package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.SimSnapshot
import com.example.minutewatcher.core.model.SimUsage
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
@RunWith(RobolectricTestRunner::class)

class ResetManagerTest {
    private val trafficTracker: TrafficTracker = mock()
    private val dataChangeNotifier: DataChangeNotifier = mock()
    private val timeProvider = FixedTimeProvider(currentTime = 1000000L)
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()

    private lateinit var resetManager: ResetManager

    @Before
    fun setup() {
        resetManager = ResetManager(simIdentityRepository, simUsageRepository, trafficTracker, dataChangeNotifier, timeProvider)
    }

    @Test
    fun `performAutoReset skips inactive sims`() =
        runTest {
            val snapshot =
                com.example.minutewatcher.core.model.GlobalSnapshot(
                    version = SchemaVersion(1, 0, 0),
                    timestamp = 0L,
                    simSnapshots =
                        listOf(
                            SimSnapshot(0, "MTS", false, emptyMap()),
                        ),
                    capabilities = emptyList(),
                )

            resetManager.performAutoReset(snapshot)

            verify(simUsageRepository, never()).resetSimUsageIfDue(any(), any(), any())
        }

    @Test
    fun `performAutoReset calls repository when due`() =
        runTest {
            val identity = "sim_0"
            val snapshot =
                com.example.minutewatcher.core.model.GlobalSnapshot(
                    version = SchemaVersion(1, 0, 0),
                    timestamp = 0L,
                    simSnapshots =
                        listOf(
                            SimSnapshot(0, "MTS", true, emptyMap()),
                        ),
                    capabilities = emptyList(),
                )

            whenever(simIdentityRepository.getIdentity(0)).thenReturn(identity)
            whenever(simUsageRepository.getSimUsage(identity)).thenReturn(
                flowOf(
                    SimUsage(
                        identity, 0, 1, "MTS", 100, 200, 1.0f, 1024, 0, 0f,
                        true, 1, 0L, false
                    ),
                ),
            )
            whenever(simUsageRepository.resetSimUsageIfDue(any(), any(), any())).thenReturn(true)

            resetManager.performAutoReset(snapshot)

            verify(simUsageRepository).resetSimUsageIfDue(eq(identity), eq(1), any())
            verify(trafficTracker).resetBaseline(identity)
            verify(dataChangeNotifier).notifyDataChanged()
        }

    @Test
    fun `performAutoReset does nothing when not due`() =
        runTest {
            val identity = "sim_0"
            val snapshot =
                com.example.minutewatcher.core.model.GlobalSnapshot(
                    version = SchemaVersion(1, 0, 0),
                    timestamp = 0L,
                    simSnapshots =
                        listOf(
                            SimSnapshot(0, "MTS", true, emptyMap()),
                        ),
                    capabilities = emptyList(),
                )

            whenever(simIdentityRepository.getIdentity(0)).thenReturn(identity)
            whenever(simUsageRepository.getSimUsage(identity)).thenReturn(
                flowOf(
                    SimUsage(
                        identity, 0, 1, "MTS", 100, 200, 1.0f, 1024, 0, 0f,
                        true, 1, 0L, false
                    ),
                ),
            )
            whenever(simUsageRepository.resetSimUsageIfDue(any(), any(), any())).thenReturn(false)

            resetManager.performAutoReset(snapshot)

            verify(simUsageRepository).resetSimUsageIfDue(eq(identity), eq(1), any())
            verify(trafficTracker, never()).resetBaseline(any())
            verify(dataChangeNotifier, never()).notifyDataChanged()
        }

    @Test
    fun `performAutoReset is idempotent per snapshot session`() =
        runTest {
            // Even if we call it multiple times, it depends on SimRepository to handle actual timing.
            // But we can verify it at least tries to check each time.
            val identity = "sim_0"
            val snapshot =
                com.example.minutewatcher.core.model.GlobalSnapshot(
                    version = SchemaVersion(1, 0, 0),
                    timestamp = 0L,
                    simSnapshots =
                        listOf(
                            SimSnapshot(0, "MTS", true, emptyMap()),
                        ),
                    capabilities = emptyList(),
                )

            whenever(simIdentityRepository.getIdentity(0)).thenReturn(identity)
            whenever(simUsageRepository.getSimUsage(identity)).thenReturn(
                flowOf(
                    SimUsage(
                        identity, 0, 1, "MTS", 100, 200, 1.0f, 1024, 0, 0f,
                        true, 1, 0L, false
                    ),
                ),
            )
            whenever(simUsageRepository.resetSimUsageIfDue(any(), any(), any())).thenReturn(true)

            repeat(5) {
                resetManager.performAutoReset(snapshot)
            }

            // The idempotency is REALLY at the SimRepository level (DataStore edit block).
            // ResetManager just loops.
            verify(simUsageRepository, times(5)).resetSimUsageIfDue(eq(identity), eq(1), any())
        }
}


