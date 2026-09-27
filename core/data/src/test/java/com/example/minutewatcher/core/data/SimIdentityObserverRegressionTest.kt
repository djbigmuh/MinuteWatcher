package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.SeedResolutionResult
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.model.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SimIdentityObserverRegressionTest {
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
    fun `regression - Persistent seed from catch block should be Real, not Fallback`() =
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
    fun `regression - Ephemeral seed from catch block should be Fallback`() =
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
}
