package com.example.minutewatcher.core.data

import android.content.Context
import com.example.minutewatcher.core.domain.SettingsRepository
import com.example.minutewatcher.core.domain.SimRepository
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SimSnapshot
import kotlinx.coroutines.flow.flowOf
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
    private lateinit var context: Context

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
        resetManager = ResetManager(context, trafficTracker, updateCoordinator)

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

            resetManager.performAutoReset(simRepository, settingsRepository, snapshot)

            verify(simRepository).resetSimUsage(identity)
            verify(simRepository).setNotifiedLowMinutes(eq(identity), any())
        }
}
