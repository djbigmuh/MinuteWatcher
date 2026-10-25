package host.serv.djbigmuh.minutewatcher.core.data

import host.serv.djbigmuh.minutewatcher.core.common.util.FixedTimeProvider
import host.serv.djbigmuh.minutewatcher.core.common.util.PermissionManager
import host.serv.djbigmuh.minutewatcher.core.common.util.initTestLogger
import host.serv.djbigmuh.minutewatcher.core.domain.DataChangeNotifier
import host.serv.djbigmuh.minutewatcher.core.domain.PermissionStateNotifier
import host.serv.djbigmuh.minutewatcher.core.domain.SimIdentityRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimUsageRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimUsageResetRepository
import host.serv.djbigmuh.minutewatcher.core.model.ActiveSimInfo
import host.serv.djbigmuh.minutewatcher.core.model.IdentitySource
import host.serv.djbigmuh.minutewatcher.core.model.SimDiscoveryResult
import host.serv.djbigmuh.minutewatcher.core.model.SimIdentity
import host.serv.djbigmuh.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class EphemeralSeedGatingTest {
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()
    private val simUsageResetRepository: SimUsageResetRepository = mock()
    private val permissionManager: PermissionManager = mock()
    private val baselineManager: TrafficBaselineManager = mock()
    private val deltaCalculator: TrafficDeltaCalculator = mock()
    private val simChangeHandler: TrafficSimChangeHandler = mock()
    private val targetResolver: TrafficTargetResolver = mock()
    private val permissionChecker: TrafficPermissionChecker = mock()
    private val permissionStateNotifier: PermissionStateNotifier = mock()
    private val dataChangeNotifier: DataChangeNotifier = mock()

    private val timeProvider = FixedTimeProvider()
    private lateinit var cache: SimIdentityCache

    @Before
    fun setup() {
        initTestLogger()
        cache = SimIdentityCache(timeProvider)
        whenever(permissionStateNotifier.changes).thenReturn(MutableStateFlow(Unit))
    }

    @Test
    fun `TrafficTracker updateTrafficUsage skips update for unstable identity`() =
        runTest {
            val tracker = createTracker(backgroundScope)
            val unstableIdentity = SimIdentity("unstable", 1, 0, IdentitySource.ICCID, isStable = false)
            val activeSims =
                listOf(
                    ActiveSimInfo(
                        identity = unstableIdentity,
                        slotIndex = 0,
                        subscriptionId = 1,
                        iccId = "unstable",
                        displayName = "Unstable SIM",
                        carrierName = "Carrier",
                        isDefaultData = true,
                        isDefaultVoice = true,
                    ),
                )

            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(activeSims))
            whenever(targetResolver.resolve(activeSims)).thenReturn(
                TrafficTargetResolver.TargetResult.Success(TargetInfo(0, 1)),
            )
            whenever(permissionChecker.checkPermissions(any())).thenReturn(true)
            whenever(simIdentityRepository.getActiveIdentities()).thenReturn(flowOf(listOf("unstable")))

            tracker.updateTrafficUsage()

            verify(simChangeHandler, never()).handleSimChangeIfNeeded(any(), any())
            assertEquals(TrackingStatus.Idle, tracker.status.value)
        }

    @Test
    fun `SimIdentityCache evicts unstable identities when full`() {
        val maxCacheSize = 64
        // 1. Fill cache with stable identities
        for (i in 1..maxCacheSize) {
            val identity = SimIdentity("stable_$i", i, i, IdentitySource.ICCID, isStable = true)
            cache.getOrCreateCanonical(identity)
        }

        // 2. Add an unstable identity
        val unstable = SimIdentity("unstable", 99, 99, IdentitySource.ICCID, isStable = false)
        val canonicalUnstable = cache.getOrCreateCanonical(unstable)
        assertTrue("Unstable identity should be cached initially", canonicalUnstable === unstable)

        // 3. Add another unstable identity to trigger eviction (size is now >= maxCacheSize)
        val unstable2 = SimIdentity("unstable2", 100, 100, IdentitySource.ICCID, isStable = false)
        cache.getOrCreateCanonical(unstable2)

        // 4. Verify 'unstable' was evicted: getOrCreateCanonical should return the NEW object, not the cached one.
        val unstableAgain = SimIdentity("unstable", 99, 99, IdentitySource.ICCID, isStable = false)
        val result = cache.getOrCreateCanonical(unstableAgain)

        assertTrue("Previous unstable should have been evicted", result === unstableAgain)
        assertFalse("Should not be the old object from cache", result === unstable)

        // 5. Stable ones should NOT be evicted
        val stableAgain = SimIdentity("stable_1", 1, 1, IdentitySource.ICCID, isStable = true)
        val stableResult = cache.getOrCreateCanonical(stableAgain)
        assertFalse("Stable identity should NOT be evicted", stableResult === stableAgain)
    }

    @Test
    fun `ActiveSimsFetcher does not persist ephemeral identities`() =
        runTest {
            val discovery: SimIdentityDiscovery = mock()
            val persistence: SimIdentityPersistence = mock()
            val fetcher = ActiveSimsFetcher(discovery, persistence, cache)

            val seed = SeedResult.Fallback("ephemeral")
            val infoList =
                listOf(
                    ActiveSimInfo(
                        identity = SimIdentity("id", 1, 0, IdentitySource.ICCID, isStable = false),
                        slotIndex = 0,
                        subscriptionId = 1,
                        iccId = "icc",
                        displayName = "SIM",
                        carrierName = "Carrier",
                        isDefaultData = true,
                        isDefaultVoice = true,
                    ),
                )
            whenever(discovery.discoverSims("ephemeral", false)).thenReturn(
                SimDiscoveryResult.Available(infoList),
            )

            val result = fetcher.fetchActiveSimsWithRetry(seed)

            assertTrue(result is SimDiscoveryResult.Available)
            verify(persistence, never()).saveSimIdentitiesInfo(any())
            verify(persistence, never()).retainMigrated(any())
        }

    @Test
    fun `CallAttributionResolver resolveIdentity filters unstable identities`() =
        runTest {
            val resolver =
                CallAttributionResolver(
                    simIdentityRepository,
                    permissionManager,
                    dataChangeNotifier,
                )

            val unstableIdentity = SimIdentity("unstable", 1, 0, IdentitySource.ICCID, isStable = false)
            val activeSims =
                listOf(
                    ActiveSimInfo(
                        identity = unstableIdentity,
                        slotIndex = 0,
                        subscriptionId = 1,
                        iccId = "unstable",
                        displayName = "Unstable SIM",
                        carrierName = "Carrier",
                        isDefaultData = true,
                        isDefaultVoice = true,
                    ),
                )
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(activeSims))

            val resolved = resolver.resolveIdentity(1)
            assertNull("Should not resolve identity if it is unstable", resolved)
        }

    private fun createTracker(scope: CoroutineScope): TrafficTracker {
        val deps =
            createTrafficTrackerDependencies(
                simIdentityRepository,
                simUsageRepository,
                simUsageResetRepository,
                permissionManager,
                baselineManager,
                deltaCalculator,
                simChangeHandler,
                targetResolver,
                permissionChecker,
                permissionStateNotifier,
                dataChangeNotifier,
                timeProvider,
            )
        return TrafficTracker(deps, scope).apply { ensureStarted() }
    }
}
