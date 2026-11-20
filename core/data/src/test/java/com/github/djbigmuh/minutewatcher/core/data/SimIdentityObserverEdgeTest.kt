package com.github.djbigmuh.minutewatcher.core.data

import com.github.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import com.github.djbigmuh.minutewatcher.core.domain.SeedResolutionResult
import com.github.djbigmuh.minutewatcher.core.domain.SimIdentitySeedRepository
import com.github.djbigmuh.minutewatcher.core.model.ActiveSimInfo
import com.github.djbigmuh.minutewatcher.core.model.AppSettings
import com.github.djbigmuh.minutewatcher.core.model.SimDiscoveryResult
import com.github.djbigmuh.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class SimIdentityObserverEdgeTest {
    private val discovery: SimIdentityDiscovery = mock()
    private val seedRepository: SimIdentitySeedRepository = mock()
    private val appSettingsRepository: AppSettingsRepository = mock()
    private val mockSimProvider: MockSimProvider = mock()
    private val fetcher: ActiveSimsFetcher = mock()
    private val cache: SimIdentityCache = mock()
    private val migrationService: IdentityMigrationService = mock()

    @Before
    fun setup() {
        whenever(mockSimProvider.getMockSims(any())).thenReturn(null)
        whenever(appSettingsRepository.observeAppSettings()).thenReturn(MutableStateFlow(AppSettings()))
        whenever(discovery.observeSubscriptionChanges()).thenReturn(flow { })
    }

    private fun createObserver(scope: CoroutineScope): SimIdentityObserver =
        SimIdentityObserver(
            discovery = discovery,
            seedRepository = seedRepository,
            appSettingsRepository = appSettingsRepository,
            mockSimProvider = mockSimProvider,
            fetcher = fetcher,
            migrationService = migrationService,
            scope = scope,
            cache = cache,
        )

    @Test
    fun `simIdentityObserver - persistent seed from catch block returns real not fallback`() =
        runTest {
            val persistentSeed = "persistent_seed"
            // 1. seedFlow throws exception
            whenever(seedRepository.seedFlow).thenReturn(flow { throw IllegalStateException("Source error") })

            // 2. Fallback returns Persistent
            whenever(seedRepository.getOrCreateSeedOrEphemeralFallback(any())).thenReturn(
                SeedResolutionResult.Persistent(persistentSeed),
            )

            val observer = createObserver(backgroundScope)

            // 3. Collect from seedFlow
            val result = observer.seedFlow.first()

            // BUG: it will be SeedResult.Fallback
            // EXPECTED: it should be SeedResult.Real
            assertTrue(
                "Expected SeedResult.Real for persistent seed, but got $result",
                result is SeedResult.Real,
            )
        }

    @Test
    fun `simIdentityObserver - ephemeral seed from catch block returns fallback`() =
        runTest {
            val ephemeralSeed = "ephemeral_seed"
            whenever(seedRepository.seedFlow).thenReturn(flow { throw IllegalStateException("Source error") })

            whenever(seedRepository.getOrCreateSeedOrEphemeralFallback(any())).thenReturn(
                SeedResolutionResult.Ephemeral(ephemeralSeed),
            )

            val observer = createObserver(backgroundScope)

            val result = observer.seedFlow.first()

            assertTrue(
                "Expected SeedResult.Fallback for ephemeral seed, but got $result",
                result is SeedResult.Fallback,
            )
        }

    @Test
    fun `successful SIM discovery clears sticky PERMISSION_MISSING error status`() =
        runTest(UnconfinedTestDispatcher()) {
            whenever(seedRepository.seedFlow).thenReturn(MutableStateFlow("test_seed"))
            whenever(seedRepository.getOrCreateSeedOrEphemeralFallback(any()))
                .thenReturn(SeedResolutionResult.Persistent("test_seed"))

            val subscriptionChanges = MutableSharedFlow<Unit>(replay = 1)
            whenever(discovery.observeSubscriptionChanges()).thenReturn(subscriptionChanges)

            whenever(fetcher.fetchActiveSimsWithRetry(any()))
                .thenReturn(SimDiscoveryResult.PermissionMissing)

            val observer = createObserver(backgroundScope)
            val collectJob = launch { observer.activeSimsCache.collect { } }

            subscriptionChanges.emit(Unit)
            testScheduler.advanceTimeBy(1000.milliseconds)

            val errorStatus = observer.status.value
            assertTrue(
                "Expected status to be Error PERMISSION_MISSING, but was $errorStatus",
                errorStatus is TrackingStatus.Error &&
                    errorStatus.type == TrackingStatus.ErrorType.PERMISSION_MISSING,
            )

            whenever(fetcher.fetchActiveSimsWithRetry(any()))
                .thenReturn(SimDiscoveryResult.Available(emptyList()))

            observer.recover()
            testScheduler.advanceTimeBy(1000.milliseconds)

            assertEquals(TrackingStatus.Active, observer.status.value)

            collectJob.cancel()
        }

    @Test
    fun `unsupported SIM discovery sets NOT_SUPPORTED status and service unavailable code`() =
        runTest(UnconfinedTestDispatcher()) {
            whenever(seedRepository.seedFlow).thenReturn(MutableStateFlow("test_seed"))
            whenever(seedRepository.getOrCreateSeedOrEphemeralFallback(any()))
                .thenReturn(SeedResolutionResult.Persistent("test_seed"))

            val subscriptionChanges = MutableSharedFlow<Unit>(replay = 1)
            whenever(discovery.observeSubscriptionChanges()).thenReturn(subscriptionChanges)

            whenever(fetcher.fetchActiveSimsWithRetry(any()))
                .thenReturn(SimDiscoveryResult.Unsupported)

            val observer = createObserver(backgroundScope)
            val emittedList = mutableListOf<List<ActiveSimInfo>>()
            val collectJob = launch { observer.activeSimsCache.collect { emittedList.add(it) } }

            subscriptionChanges.emit(Unit)
            testScheduler.advanceTimeBy(1000.milliseconds)

            val status = observer.status.value
            assertTrue(
                "Expected status to be Error NOT_SUPPORTED, but was $status",
                status is TrackingStatus.Error &&
                    status.type == TrackingStatus.ErrorType.NOT_SUPPORTED &&
                    status.code == TrackingStatus.ErrorCode.SERVICE_UNAVAILABLE,
            )
            assertTrue(emittedList.isNotEmpty() && emittedList.last().isEmpty())

            collectJob.cancel()
        }
}
