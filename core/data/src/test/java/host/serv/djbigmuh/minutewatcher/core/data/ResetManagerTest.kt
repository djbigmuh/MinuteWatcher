package host.serv.djbigmuh.minutewatcher.core.data

import host.serv.djbigmuh.minutewatcher.core.common.util.FixedTimeProvider
import host.serv.djbigmuh.minutewatcher.core.domain.DataChangeNotifier
import host.serv.djbigmuh.minutewatcher.core.domain.SimIdentityRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimUsageRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimUsageResetRepository
import host.serv.djbigmuh.minutewatcher.core.model.ActiveSimInfo
import host.serv.djbigmuh.minutewatcher.core.model.IdentitySource
import host.serv.djbigmuh.minutewatcher.core.model.SimIdentity
import host.serv.djbigmuh.minutewatcher.core.model.SimUsage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
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
        resetManager =
            ResetManager(
                simIdentityRepository,
                simUsageRepository,
                simUsageResetRepository,
                trafficTracker,
                dataChangeNotifier,
                timeProvider,
            )
    }

    @Test
    fun `performAutoReset skips inactive sims`() =
        runTest {
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(emptyList()))

            resetManager.performAutoReset()

            verify(simUsageResetRepository, never()).resetSimUsageIfDue(any(), any(), any())
        }

    @Test
    fun `performAutoReset calls repository when due`() =
        runTest {
            val identity = "sim_0"
            val mockActiveSim =
                ActiveSimInfo(
                    identity = SimIdentity(identity, 1, 0, IdentitySource.ICCID, isStable = true),
                    slotIndex = 0,
                    subscriptionId = 1,
                    iccId = identity,
                    displayName = "MTS",
                    carrierName = "MTS",
                    isDefaultData = true,
                    isDefaultVoice = true,
                )
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(listOf(mockActiveSim)))

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
                        manualAdjustment = 0,
                        trafficAdjustmentGb = 0f,
                        autoReset = true,
                        autoResetDay = 1,
                        lastResetDate = 0L,
                        rolloverEnabled = false,
                    ),
                ),
            )
            whenever(simUsageResetRepository.resetSimUsageIfDue(any(), any(), any())).thenReturn(true)

            resetManager.performAutoReset()

            verify(simUsageResetRepository).resetSimUsageIfDue(eq(identity), eq(1), any())
            verify(trafficTracker).resetBaseline(identity)
            verify(dataChangeNotifier).notifyDataChanged()
        }

    @Test
    fun `performAutoReset does nothing when not due`() =
        runTest {
            val identity = "sim_0"
            val mockActiveSim =
                ActiveSimInfo(
                    identity = SimIdentity(identity, 1, 0, IdentitySource.ICCID, isStable = true),
                    slotIndex = 0,
                    subscriptionId = 1,
                    iccId = identity,
                    displayName = "MTS",
                    carrierName = "MTS",
                    isDefaultData = true,
                    isDefaultVoice = true,
                )
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(listOf(mockActiveSim)))

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
                        manualAdjustment = 0,
                        trafficAdjustmentGb = 0f,
                        autoReset = true,
                        autoResetDay = 1,
                        lastResetDate = 0L,
                        rolloverEnabled = false,
                    ),
                ),
            )
            whenever(simUsageResetRepository.resetSimUsageIfDue(any(), any(), any())).thenReturn(false)

            resetManager.performAutoReset()

            verify(simUsageResetRepository).resetSimUsageIfDue(eq(identity), eq(1), any())
            verify(trafficTracker, never()).resetBaseline(any())
            verify(dataChangeNotifier, never()).notifyDataChanged()
        }

    @Test
    fun `performAutoReset is optimized for repeated calls`() =
        runTest {
            val identity = "sim_0"
            val mockActiveSim =
                ActiveSimInfo(
                    identity = SimIdentity(identity, 1, 0, IdentitySource.ICCID, isStable = true),
                    slotIndex = 0,
                    subscriptionId = 1,
                    iccId = identity,
                    displayName = "MTS",
                    carrierName = "MTS",
                    isDefaultData = true,
                    isDefaultVoice = true,
                )
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(listOf(mockActiveSim)))

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
                        manualAdjustment = 0,
                        trafficAdjustmentGb = 0f,
                        autoReset = true,
                        autoResetDay = 1,
                        lastResetDate = 0L,
                        rolloverEnabled = false,
                    ),
                ),
            )
            whenever(simUsageResetRepository.resetSimUsageIfDue(any(), any(), any())).thenReturn(true)

            // First call should trigger check
            resetManager.performAutoReset()

            // Second call immediately after should skip check
            resetManager.performAutoReset()

            // Verify only one check happened
            verify(simUsageResetRepository, times(1)).resetSimUsageIfDue(eq(identity), eq(1), any())

            // Advance time beyond 1 hour (3600000ms)
            timeProvider.advance(3600001L)

            // Third call after time advance should trigger check again
            resetManager.performAutoReset()

            verify(simUsageResetRepository, times(2)).resetSimUsageIfDue(eq(identity), eq(1), any())
        }

    @Test
    fun `performAutoReset handles concurrent calls correctly`() =
        runTest {
            val identity = "sim_0"
            val mockActiveSim =
                ActiveSimInfo(
                    identity = SimIdentity(identity, 1, 0, IdentitySource.ICCID, isStable = true),
                    slotIndex = 0,
                    subscriptionId = 1,
                    iccId = identity,
                    displayName = "MTS",
                    carrierName = "MTS",
                    isDefaultData = true,
                    isDefaultVoice = true,
                )
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(listOf(mockActiveSim)))

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
                        manualAdjustment = 0,
                        trafficAdjustmentGb = 0f,
                        autoReset = true,
                        autoResetDay = 1,
                        lastResetDate = 0L,
                        rolloverEnabled = false,
                    ),
                ),
            )
            whenever(simUsageResetRepository.resetSimUsageIfDue(any(), any(), any())).thenReturn(true)

            // Launch two concurrent calls.
            // Even though performAutoReset uses a Mutex internally, this verifies that the atomic
            // lastResetCheck map properly skips the second check after the first one completes.
            val d1 = async { resetManager.performAutoReset() }
            val d2 = async { resetManager.performAutoReset() }

            d1.await()
            d2.await()

            // Verify only one repository call happened despite concurrent high-level calls
            verify(simUsageResetRepository, times(1)).resetSimUsageIfDue(eq(identity), eq(1), any())
        }

    @Test
    fun `performAutoReset concurrent calls for multiple SIMs result in single reset per SIM`() =
        runTest {
            val id0 = "sim_0"
            val id1 = "sim_1"
            val sim0 = createMockSimInfo(id0, 0)
            val sim1 = createMockSimInfo(id1, 1)

            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(listOf(sim0, sim1)))
            whenever(simUsageRepository.getSimUsage(any())).thenAnswer {
                val id = it.arguments[0] as String
                flowOf(createMockSimUsage(id))
            }
            whenever(simUsageResetRepository.resetSimUsageIfDue(any(), any(), any())).thenReturn(true)

            // Launch two concurrent calls
            val d1 = async { resetManager.performAutoReset() }
            val d2 = async { resetManager.performAutoReset() }

            d1.await()
            d2.await()

            // Verify exactly one reset call per SIM
            verify(simUsageResetRepository, times(1)).resetSimUsageIfDue(eq(id0), any(), any())
            verify(simUsageResetRepository, times(1)).resetSimUsageIfDue(eq(id1), any(), any())
            verify(dataChangeNotifier, times(1)).notifyDataChanged()
        }

    @Test
    fun `performAutoReset returns false on partial failure`() =
        runTest {
            val id0 = "sim_0"
            val id1 = "sim_1"
            val sim0 = createMockSimInfo(id0, 0)
            val sim1 = createMockSimInfo(id1, 1)

            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(listOf(sim0, sim1)))
            whenever(simUsageRepository.getSimUsage(id0)).thenReturn(flowOf(createMockSimUsage(id0)))
            whenever(simUsageRepository.getSimUsage(id1)).thenAnswer { throw IOException("Test failure") }

            whenever(simUsageResetRepository.resetSimUsageIfDue(eq(id0), any(), any())).thenReturn(true)

            val result = resetManager.performAutoReset()

            assert(!result)
            verify(simUsageResetRepository, times(1)).resetSimUsageIfDue(eq(id0), any(), any())
            verify(trafficTracker).resetBaseline(id0)
        }

    @Test
    fun `performAutoReset activates cooling down even after IOException`() =
        runTest {
            val identity = "sim_0"
            val sim = createMockSimInfo(identity, 0)
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(listOf(sim)))
            whenever(simUsageRepository.getSimUsage(identity)).thenReturn(flowOf(createMockSimUsage(identity)))

            // First call fails with IOException
            whenever(simUsageResetRepository.resetSimUsageIfDue(any(), any(), any()))
                .thenAnswer { throw IOException("Disk error") }

            val result1 = resetManager.performAutoReset()
            assert(!result1)
            verify(simUsageResetRepository, times(1)).resetSimUsageIfDue(eq(identity), any(), any())

            // Second call immediately after should be SKIPPED because lastResetCheck was updated in finally
            whenever(simUsageResetRepository.resetSimUsageIfDue(any(), any(), any()))
                .thenReturn(true)

            val result2 = resetManager.performAutoReset()
            // Result is true because skipped call is not considered a failure of coordinator logic
            assert(result2)
            // Still only 1 call to repository
            verify(simUsageResetRepository, times(1)).resetSimUsageIfDue(eq(identity), any(), any())

            // Third call after cooling down should trigger check again
            timeProvider.advance(3600001L)
            resetManager.performAutoReset()
            verify(simUsageResetRepository, times(2)).resetSimUsageIfDue(eq(identity), any(), any())
        }

    @Test
    fun `performAutoReset concurrent calls result in single check despite late timestamp update`() =
        runTest {
            val identity = "sim_0"
            val sim = createMockSimInfo(identity, 0)
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(listOf(sim)))
            whenever(simUsageRepository.getSimUsage(identity)).thenReturn(flowOf(createMockSimUsage(identity)))

            // To check atomicity, we rely on pendingResets blocking the second call
            // even if the first one hasn't completed yet.
            // In runTest, d1 and d2 calls will be launched sequentially in virtual time,
            // but since d1 "hangs" in the mock (if we added delay), d2 should return immediately.

            whenever(simUsageResetRepository.resetSimUsageIfDue(any(), any(), any())).thenAnswer {
                true
            }

            val d1 = async { resetManager.performAutoReset() }
            val d2 = async { resetManager.performAutoReset() }

            d1.await()
            d2.await()

            // Verify the repository is called once
            verify(simUsageResetRepository, times(1)).resetSimUsageIfDue(eq(identity), any(), any())
        }

    @Test
    fun `performAutoReset returns false when getSimUsage times out for one SIM`() =
        runTest {
            val id0 = "sim_0"
            val id1 = "sim_1"
            val sim0 = createMockSimInfo(id0, 0)
            val sim1 = createMockSimInfo(id1, 1)

            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(listOf(sim0, sim1)))

            // sim0 succeeds
            whenever(simUsageRepository.getSimUsage(id0)).thenReturn(flowOf(createMockSimUsage(id0)))
            whenever(simUsageResetRepository.resetSimUsageIfDue(eq(id0), any(), any())).thenReturn(true)

            // sim1 times out (Flow that never emits)
            whenever(simUsageRepository.getSimUsage(id1)).thenReturn(MutableSharedFlow())

            val result = resetManager.performAutoReset()

            // Result should be false because sim1 was skipped
            assertFalse("performAutoReset should return false if any SIM is skipped", result)

            // Verify sim0 was still processed
            verify(simUsageResetRepository).resetSimUsageIfDue(eq(id0), any(), any())
            verify(trafficTracker).resetBaseline(id0)
        }

    @Test
    fun `performAutoReset returns true on cooldown skip`() =
        runTest {
            val identity = "sim_0"
            val sim = createMockSimInfo(identity, 0)
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(listOf(sim)))
            whenever(simUsageRepository.getSimUsage(identity)).thenReturn(flowOf(createMockSimUsage(identity)))
            whenever(simUsageResetRepository.resetSimUsageIfDue(any(), any(), any())).thenReturn(true)

            // First call - success
            assertTrue(resetManager.performAutoReset())

            // Second call immediately after - skipped due to cooldown
            val result = resetManager.performAutoReset()

            assertTrue("Cooldown skip should still return true", result)
            // Still only 1 call to repository
            verify(simUsageResetRepository, times(1)).resetSimUsageIfDue(eq(identity), any(), any())
        }

    private fun createMockSimInfo(
        identity: String,
        slot: Int,
    ) = ActiveSimInfo(
        identity = SimIdentity(identity, slot + 1, slot, IdentitySource.ICCID, isStable = true),
        slotIndex = slot,
        subscriptionId = slot + 1,
        iccId = identity,
        displayName = "Op $slot",
        carrierName = "Op $slot",
        isDefaultData = slot == 0,
        isDefaultVoice = slot == 0,
    )

    private fun createMockSimUsage(identity: String) =
        SimUsage(
            identity,
            0,
            1,
            "MTS",
            100,
            200,
            1.0f,
            1024,
            manualAdjustment = 0,
            trafficAdjustmentGb = 0f,
            autoReset = true,
            autoResetDay = 1,
            lastResetDate = 0L,
            rolloverEnabled = false,
        )
}
