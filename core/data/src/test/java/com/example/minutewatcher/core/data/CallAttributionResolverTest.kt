package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.IdentitySource
import com.example.minutewatcher.core.model.SimIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class CallAttributionResolverTest {
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val permissionManager: PermissionManager = mock()
    private val dataChangeNotifier =
        object : DataChangeNotifier {
            override suspend fun notifyDataChanged(
                updateWidget: Boolean,
                force: Boolean,
            ): Boolean = true
        }

    private lateinit var resolver: CallAttributionResolver

    @Before
    fun setUp() {
        resolver =
            CallAttributionResolver(
                simIdentityRepository = simIdentityRepository,
                permissionManager = permissionManager,
                dataChangeNotifier = dataChangeNotifier,
            )
    }

    @Test
    fun `concurrent attribution failures increment counter atomically without lost updates`() =
        runBlocking {
            val subId = 1
            val coroutineCount = 8
            val iterationsPerCoroutine = 250

            val jobs =
                List(coroutineCount) {
                    launch(Dispatchers.Default) {
                        repeat(iterationsPerCoroutine) {
                            resolver.handleAttributionFailure(
                                subId = subId,
                                updateStatus = {},
                                cleanupAction = {},
                            )
                        }
                    }
                }

            withTimeout(10.seconds) {
                jobs.joinAll()
            }

            assertEquals(coroutineCount * iterationsPerCoroutine, resolver.getFailureCount(subId))
        }

    @Test
    fun `resolveTargetIdentity selects SIM with lowest slotIndex regardless of list order`() =
        runBlocking {
            val simSlot0 =
                ActiveSimInfo(
                    identity = SimIdentity("sim_slot_0", 10, 0, IdentitySource.ICCID, isStable = true),
                    slotIndex = 0,
                    subscriptionId = 10,
                    iccId = "icc_0",
                    displayName = "SIM 0",
                    carrierName = "C0",
                    mccMnc = "25001",
                    isDefaultData = false,
                    isDefaultVoice = false,
                )
            val simSlot1 =
                ActiveSimInfo(
                    identity = SimIdentity("sim_slot_1", 20, 1, IdentitySource.ICCID, isStable = true),
                    slotIndex = 1,
                    subscriptionId = 20,
                    iccId = "icc_1",
                    displayName = "SIM 1",
                    carrierName = "C1",
                    mccMnc = "25002",
                    isDefaultData = false,
                    isDefaultVoice = false,
                )

            // Order 1: slot 0, then slot 1
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(listOf(simSlot0, simSlot1)))
            val (identity1, isFallback1) = resolver.resolveTargetIdentity(subId = 999, savedIdentity = null)
            assertEquals("sim_slot_0", identity1)
            assertTrue(isFallback1)

            // Order 2: slot 1, then slot 0
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(listOf(simSlot1, simSlot0)))
            val (identity2, isFallback2) = resolver.resolveTargetIdentity(subId = 999, savedIdentity = null)
            assertEquals("sim_slot_0", identity2)
            assertTrue(isFallback2)
        }

    @Test
    fun `resolveTargetIdentity selects SIM with lowest subscriptionId when slotIndex is equal`() =
        runBlocking {
            val simSub10 =
                ActiveSimInfo(
                    identity = SimIdentity("sim_sub_10", 10, 0, IdentitySource.ICCID, isStable = true),
                    slotIndex = 0,
                    subscriptionId = 10,
                    iccId = "icc_10",
                    displayName = "SIM 10",
                    carrierName = "C10",
                    mccMnc = "25001",
                    isDefaultData = false,
                    isDefaultVoice = false,
                )
            val simSub5 =
                ActiveSimInfo(
                    identity = SimIdentity("sim_sub_5", 5, 0, IdentitySource.ICCID, isStable = true),
                    slotIndex = 0,
                    subscriptionId = 5,
                    iccId = "icc_5",
                    displayName = "SIM 5",
                    carrierName = "C5",
                    mccMnc = "25001",
                    isDefaultData = false,
                    isDefaultVoice = false,
                )

            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(listOf(simSub10, simSub5)))
            val (identity, isFallback) = resolver.resolveTargetIdentity(subId = 999, savedIdentity = null)
            assertEquals("sim_sub_5", identity)
            assertTrue(isFallback)
        }
}
