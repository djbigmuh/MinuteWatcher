package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.domain.SettingsRepository
import com.example.minutewatcher.core.domain.SimRepository
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SimSnapshot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResetManagerTest {
    @Mock
    private lateinit var updateCoordinator: UpdateCoordinator

    @Mock
    private lateinit var trafficTracker: TrafficTracker

    @Mock
    private lateinit var simRepository: SimRepository

    @Mock
    private lateinit var settingsRepository: SettingsRepository

    private lateinit var resetManager: ResetManager

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        resetManager = ResetManager(trafficTracker, updateCoordinator)

        runTest {
            whenever(simRepository.getIdentity(0)).thenReturn("sim_0")
            whenever(simRepository.getIdentity(1)).thenReturn("sim_1")
        }
    }

    private fun createSnapshot(slots: List<Int>): GlobalSnapshot =
        GlobalSnapshot(
            version =
                com.example.minutewatcher.core.model
                    .SchemaVersion(major = 1, minor = 0, patch = 0),
            timestamp = System.currentTimeMillis(),
            simSnapshots =
                slots.map {
                    SimSnapshot(it, "MTS", true, emptyMap())
                },
            capabilities = emptyList(),
        )

    @Test
    fun testPerformAutoReset_NoActiveSim() =
        runTest {
            val snapshot =
                GlobalSnapshot(
                    version =
                        com.example.minutewatcher.core.model
                            .SchemaVersion(major = 1, minor = 0, patch = 0),
                    timestamp = System.currentTimeMillis(),
                    simSnapshots = emptyList(),
                    capabilities = emptyList(),
                )

            resetManager.performAutoReset(simRepository, settingsRepository, snapshot)

            verify(simRepository, never()).resetSimUsage(any())
        }

    @Test
    fun testPerformAutoReset_ShouldReset() =
        runTest {
            val identity = "sim_0"
            val snapshot = createSnapshot(listOf(0))

            whenever(simRepository.getSimUsage(identity)).thenReturn(
                flowOf(
                    com.example.minutewatcher.core.model.SimUsage(
                        identity = identity,
                        slotIndex = 0,
                        subscriptionId = 1,
                        operator = "MTS",
                        minutesUsed = 100,
                        minutesLimit = 500,
                        trafficUsed = 1024f,
                        trafficLimitMb = 5120,
                        manualAdjustment = 0,
                        trafficAdjustment = 0f,
                        autoReset = true,
                        autoResetDay = 1,
                        lastResetDate = 0L,
                    ),
                ),
            )
            whenever(simRepository.resetSimUsageIfDue(any(), any(), any())).thenReturn(true)

            resetManager.performAutoReset(simRepository, settingsRepository, snapshot)

            verify(simRepository).resetSimUsageIfDue(eq(identity), eq(1), any())
        }

    @Test
    fun testPerformAutoReset_NotDue() =
        runTest {
            val identity = "sim_0"
            val snapshot = createSnapshot(listOf(0))

            whenever(simRepository.getSimUsage(identity)).thenReturn(
                flowOf(
                    com.example.minutewatcher.core.model.SimUsage(
                        identity = identity,
                        slotIndex = 0,
                        subscriptionId = 1,
                        operator = "MTS",
                        minutesUsed = 100,
                        minutesLimit = 500,
                        trafficUsed = 1024f,
                        trafficLimitMb = 5120,
                        manualAdjustment = 0,
                        trafficAdjustment = 0f,
                        autoReset = true,
                        autoResetDay = 1,
                        lastResetDate = System.currentTimeMillis(), // Already reset recently
                    ),
                ),
            )
            whenever(simRepository.resetSimUsageIfDue(any(), any(), any())).thenReturn(false)

            resetManager.performAutoReset(simRepository, settingsRepository, snapshot)

            // Should still call the method to check, but we verify what happens when it returns false
            verify(simRepository).resetSimUsageIfDue(eq(identity), eq(1), any())
            verify(updateCoordinator, never()).notifyDataChanged(any(), any())
        }

    @Test
    fun testPerformAutoReset_ConcurrentCalls() =
        runTest {
            val identity = "sim_0"
            val snapshot = createSnapshot(listOf(0))

            whenever(simRepository.getSimUsage(identity)).thenReturn(
                flowOf(
                    com.example.minutewatcher.core.model.SimUsage(
                        identity = identity,
                        slotIndex = 0,
                        subscriptionId = 1,
                        operator = "MTS",
                        minutesUsed = 100,
                        minutesLimit = 500,
                        trafficUsed = 1024f,
                        trafficLimitMb = 5120,
                        manualAdjustment = 0,
                        trafficAdjustment = 0f,
                        autoReset = true,
                        autoResetDay = 1,
                        lastResetDate = 0L,
                    ),
                ),
            )
            whenever(simRepository.resetSimUsageIfDue(any(), any(), any())).thenReturn(true)

            // Launch multiple resets concurrently
            val jobs = List(5) {
                launch {
                    resetManager.performAutoReset(simRepository, settingsRepository, snapshot)
                }
            }
            joinAll(*jobs.toTypedArray())

            // With Mutex in ResetManager, we still might have multiple calls to performAutoReset,
            // but they will wait for each other.
            // However, verify(..., times(5)) would be wrong if we expect them to be sequential and all attempt reset.
            // The idempotency is REALLY at the SimRepository level (DataStore edit block).
            // But Mutex helps avoiding multiple first() reads and calculations in parallel.
            
            verify(simRepository, times(5)).resetSimUsageIfDue(eq(identity), eq(1), any())
        }
}
