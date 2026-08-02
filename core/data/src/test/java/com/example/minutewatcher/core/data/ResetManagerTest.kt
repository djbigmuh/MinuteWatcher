package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.FixedTimeProvider
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.SimUsageResetRepository
import com.example.minutewatcher.core.model.IdentitySource
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.SimIdentity
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
    private val timeProvider = FixedTimeProvider(currentTime = 5000000L)
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()
    private val simUsageResetRepository: SimUsageResetRepository = mock()

    private lateinit var resetManager: ResetManager

    @Before
    fun setup() {
        resetManager = ResetManager(simIdentityRepository, simUsageRepository, simUsageResetRepository, trafficTracker, dataChangeNotifier, timeProvider)
    }

    @Test
    fun `performAutoReset skips inactive sims`() =
        runTest {
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(emptyList()))

            val snapshot =
                com.example.minutewatcher.core.model.GlobalSnapshot(
                    version = SchemaVersion(1, 0, 0),
                    timestamp = 0L,
                    simSnapshots =
                        listOf(
                            SimSnapshot(0, "sim_0", "MTS", false, emptyMap()),
                        ),
                    capabilities = emptyList(),
                )

            resetManager.performAutoReset(snapshot)

            verify(simUsageResetRepository, never()).resetSimUsageIfDue(any(), any(), any())
        }

    @Test
    fun `performAutoReset calls repository when due`() =
        runTest {
            val identity = "sim_0"
            val mockActiveSim =
                com.example.minutewatcher.core.model.ActiveSimInfo(
                    identity = SimIdentity(identity, 1, 0, IdentitySource.ICCID),
                    slotIndex = 0,
                    subscriptionId = 1,
                    displayName = "MTS",
                    carrierName = "MTS",
                    mccMnc = "25001",
                    isDefaultData = true,
                    isDefaultVoice = true,
                )
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(listOf(mockActiveSim)))

            val snapshot =
                com.example.minutewatcher.core.model.GlobalSnapshot(
                    version = SchemaVersion(1, 0, 0),
                    timestamp = 0L,
                    simSnapshots =
                        listOf(
                            SimSnapshot(0, "sim_0", "MTS", true, emptyMap()),
                        ),
                    capabilities = emptyList(),
                )

            whenever(simUsageRepository.getSimUsage(identity)).thenReturn(
                flowOf(
                    SimUsage(
                        identity,
                        0,
                        1,
                        "MTS",
                        100,
                        200,
                        1.0f,
                        1024,
                        0,
                        0f,
                        true,
                        1,
                        0L,
                        false,
                    ),
                ),
            )
            whenever(simUsageResetRepository.resetSimUsageIfDue(any(), any(), any())).thenReturn(true)

            resetManager.performAutoReset(snapshot)

            verify(simUsageResetRepository).resetSimUsageIfDue(eq(identity), eq(1), any())
            verify(trafficTracker).resetBaseline(identity)
            verify(dataChangeNotifier).notifyDataChanged()
        }

    @Test
    fun `performAutoReset does nothing when not due`() =
        runTest {
            val identity = "sim_0"
            val mockActiveSim =
                com.example.minutewatcher.core.model.ActiveSimInfo(
                    identity = SimIdentity(identity, 1, 0, IdentitySource.ICCID),
                    slotIndex = 0,
                    subscriptionId = 1,
                    displayName = "MTS",
                    carrierName = "MTS",
                    mccMnc = "25001",
                    isDefaultData = true,
                    isDefaultVoice = true,
                )
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(listOf(mockActiveSim)))

            val snapshot =
                com.example.minutewatcher.core.model.GlobalSnapshot(
                    version = SchemaVersion(1, 0, 0),
                    timestamp = 0L,
                    simSnapshots =
                        listOf(
                            SimSnapshot(0, "sim_0", "MTS", true, emptyMap()),
                        ),
                    capabilities = emptyList(),
                )

            whenever(simUsageRepository.getSimUsage(identity)).thenReturn(
                flowOf(
                    SimUsage(
                        identity,
                        0,
                        1,
                        "MTS",
                        100,
                        200,
                        1.0f,
                        1024,
                        0,
                        0f,
                        true,
                        1,
                        0L,
                        false,
                    ),
                ),
            )
            whenever(simUsageResetRepository.resetSimUsageIfDue(any(), any(), any())).thenReturn(false)

            resetManager.performAutoReset(snapshot)

            verify(simUsageResetRepository).resetSimUsageIfDue(eq(identity), eq(1), any())
            verify(trafficTracker, never()).resetBaseline(any())
            verify(dataChangeNotifier, never()).notifyDataChanged()
        }

    @Test
    fun `performAutoReset is optimized for repeated calls`() =
        runTest {
            val identity = "sim_0"
            val mockActiveSim =
                com.example.minutewatcher.core.model.ActiveSimInfo(
                    identity = SimIdentity(identity, 1, 0, IdentitySource.ICCID),
                    slotIndex = 0,
                    subscriptionId = 1,
                    displayName = "MTS",
                    carrierName = "MTS",
                    mccMnc = "25001",
                    isDefaultData = true,
                    isDefaultVoice = true,
                )
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(listOf(mockActiveSim)))

            val snapshot =
                com.example.minutewatcher.core.model.GlobalSnapshot(
                    version = SchemaVersion(1, 0, 0),
                    timestamp = 0L,
                    simSnapshots =
                        listOf(
                            SimSnapshot(0, "sim_0", "MTS", true, emptyMap()),
                        ),
                    capabilities = emptyList(),
                )

            whenever(simUsageRepository.getSimUsage(identity)).thenReturn(
                flowOf(
                    SimUsage(
                        identity,
                        0,
                        1,
                        "MTS",
                        100,
                        200,
                        1.0f,
                        1024,
                        0,
                        0f,
                        true,
                        1,
                        0L,
                        false,
                    ),
                ),
            )
            whenever(simUsageResetRepository.resetSimUsageIfDue(any(), any(), any())).thenReturn(true)

            // First call should trigger check
            resetManager.performAutoReset(snapshot)

            // Second call immediately after should skip check
            resetManager.performAutoReset(snapshot)

            // Verify only one check happened
            verify(simUsageResetRepository, times(1)).resetSimUsageIfDue(eq(identity), eq(1), any())

            // Advance time beyond 1 hour (3600000ms)
            timeProvider.advance(3600001L)

            // Third call after time advance should trigger check again
            resetManager.performAutoReset(snapshot)

            verify(simUsageResetRepository, times(2)).resetSimUsageIfDue(eq(identity), eq(1), any())
        }
}
