package com.github.djbigmuh.minutewatcher.core.data

import com.github.djbigmuh.minutewatcher.core.common.util.PermissionManager
import com.github.djbigmuh.minutewatcher.core.domain.DataChangeNotifier
import com.github.djbigmuh.minutewatcher.core.domain.SimIdentityRepository
import com.github.djbigmuh.minutewatcher.core.model.ActiveSimInfo
import com.github.djbigmuh.minutewatcher.core.model.IdentitySource
import com.github.djbigmuh.minutewatcher.core.model.SimIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

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
        runTest {
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

            jobs.joinAll()

            assertEquals(coroutineCount * iterationsPerCoroutine, resolver.getFailureCount(subId))
        }

    @Test
    fun `resolveTargetIdentity returns null and false when no match and multiple active sims`() =
        runTest {
            val sim0 =
                ActiveSimInfo(
                    SimIdentity("sim_0", 1, 0, IdentitySource.ICCID, true),
                    0,
                    1,
                    "i0",
                    "S0",
                    "C0",
                    false,
                    false,
                )
            val sim1 =
                ActiveSimInfo(
                    SimIdentity("sim_1", 2, 1, IdentitySource.ICCID, true),
                    1,
                    2,
                    "i1",
                    "S1",
                    "C1",
                    false,
                    false,
                )
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(listOf(sim0, sim1)))
            val (identity, isFallback) =
                resolver.resolveTargetIdentity(subId = 999, savedIdentity = null)
            assertEquals(null, identity)
            assertTrue(!isFallback)
        }

    @Test
    fun `resolveTargetIdentity returns fallback sim and true when no match and single active sim is stable`() =
        runTest {
            val sim0 =
                ActiveSimInfo(
                    SimIdentity("sim_0", 1, 0, IdentitySource.ICCID, true),
                    0,
                    1,
                    "i0",
                    "S0",
                    "C0",
                    false,
                    false,
                )
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(listOf(sim0)))
            val (identity, isFallback) =
                resolver.resolveTargetIdentity(subId = 999, savedIdentity = null)
            assertEquals("sim_0", identity)
            assertTrue(isFallback)
        }

    @Test
    fun `resolveTargetIdentity returns null and false when no match and single active sim is unstable`() =
        runTest {
            val sim0 =
                ActiveSimInfo(
                    SimIdentity("sub_0", 1, 0, IdentitySource.SUBSCRIPTION_ID, false),
                    0,
                    1,
                    null,
                    "S0",
                    "C0",
                    false,
                    false,
                )
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(listOf(sim0)))
            val (identity, isFallback) =
                resolver.resolveTargetIdentity(subId = 999, savedIdentity = null)
            assertEquals(null, identity)
            assertTrue(!isFallback)
        }

    @Test
    fun `resolveIdentity returns stableId for stable SIM and null for unstable SIM`() =
        runTest {
            val simUnstable =
                ActiveSimInfo(
                    SimIdentity("sub_1", 1, 0, IdentitySource.SUBSCRIPTION_ID, false),
                    0,
                    1,
                    null,
                    "S1",
                    "C1",
                    false,
                    false,
                )
            val simStable =
                ActiveSimInfo(
                    SimIdentity("icc_2", 2, 1, IdentitySource.ICCID, true),
                    1,
                    2,
                    "icc_2",
                    "S2",
                    "C2",
                    false,
                    false,
                )
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(listOf(simUnstable, simStable)))

            val resolvedUnstable = resolver.resolveIdentity(1)
            val resolvedStable = resolver.resolveIdentity(2)

            assertEquals(null, resolvedUnstable)
            assertEquals("icc_2", resolvedStable)
        }

    @Test
    fun `resolveTargetIdentity completes with unresolved identity when active sims source stalls`() =
        runTest {
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flow { awaitCancellation() })
            val (identity, isFallback) =
                resolver.resolveTargetIdentity(subId = 999, savedIdentity = null)
            assertEquals(null, identity)
            assertTrue(!isFallback)
        }

    @Test
    fun `resolveTargetIdentity returns stable identity of sim matched by subId when primary read found none`() =
        runTest {
            val sim =
                ActiveSimInfo(
                    SimIdentity("icc_stable", 7, 0, IdentitySource.ICCID, true),
                    0,
                    7,
                    "i7",
                    "S7",
                    "C7",
                    false,
                    false,
                )
            whenever(simIdentityRepository.observeActiveSims())
                .thenReturn(emptyFlow(), flowOf(listOf(sim)))

            val (identity, isFallback) = resolver.resolveTargetIdentity(subId = 7, savedIdentity = null)

            assertEquals("icc_stable", identity)
            assertFalse(isFallback)
        }

    @Test
    fun `resolveTargetIdentity refuses attribution when sim matched by subId has unstable identity`() =
        runTest {
            val sim =
                ActiveSimInfo(
                    SimIdentity("sub_7", 7, 0, IdentitySource.SUBSCRIPTION_ID, false),
                    0,
                    7,
                    "i7",
                    "S7",
                    "C7",
                    false,
                    false,
                )
            whenever(simIdentityRepository.observeActiveSims())
                .thenReturn(emptyFlow(), flowOf(listOf(sim)))

            val (identity, isFallback) = resolver.resolveTargetIdentity(subId = 7, savedIdentity = null)

            assertNull(identity)
            assertFalse(isFallback)
        }
}
