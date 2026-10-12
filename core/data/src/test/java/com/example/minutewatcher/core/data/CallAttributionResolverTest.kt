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
    fun `resolveTargetIdentity returns null and false when no match and multiple active sims`() =
        runBlocking {
            val sim0 =
                ActiveSimInfo(
                    SimIdentity("sim_0", 1, 0, IdentitySource.ICCID, true),
                    0,
                    1,
                    "i0",
                    "S0",
                    "C0",
                    "m0",
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
                    "m1",
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
        runBlocking {
            val sim0 =
                ActiveSimInfo(
                    SimIdentity("sim_0", 1, 0, IdentitySource.ICCID, true),
                    0,
                    1,
                    "i0",
                    "S0",
                    "C0",
                    "m0",
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
        runBlocking {
            val sim0 =
                ActiveSimInfo(
                    SimIdentity("sub_0", 1, 0, IdentitySource.SUBSCRIPTION_ID, false),
                    0,
                    1,
                    null,
                    "S0",
                    "C0",
                    "m0",
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
        runBlocking {
            val simUnstable =
                ActiveSimInfo(
                    SimIdentity("sub_1", 1, 0, IdentitySource.SUBSCRIPTION_ID, false),
                    0,
                    1,
                    null,
                    "S1",
                    "C1",
                    "m1",
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
                    "m2",
                    false,
                    false,
                )
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(listOf(simUnstable, simStable)))

            val resolvedUnstable = resolver.resolveIdentity(1)
            val resolvedStable = resolver.resolveIdentity(2)

            assertEquals(null, resolvedUnstable)
            assertEquals("icc_2", resolvedStable)
        }
}
