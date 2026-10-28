package host.serv.djbigmuh.minutewatcher.core.data

import android.content.Context
import android.telephony.SubscriptionManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import host.serv.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import host.serv.djbigmuh.minutewatcher.core.common.util.PermissionManager
import host.serv.djbigmuh.minutewatcher.core.common.util.TimeProvider
import host.serv.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import host.serv.djbigmuh.minutewatcher.core.domain.PermissionStateNotifier
import host.serv.djbigmuh.minutewatcher.core.domain.SeedResolutionResult
import host.serv.djbigmuh.minutewatcher.core.domain.SimIdentitySeedRepository
import host.serv.djbigmuh.minutewatcher.core.model.AppSettings
import host.serv.djbigmuh.minutewatcher.core.model.SimDiscoveryResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor
import kotlin.time.Duration.Companion.milliseconds

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SimIdentityRepositoryRegistrationTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var context: Context
    private lateinit var subscriptionManager: SubscriptionManager
    private lateinit var permissionManager: PermissionManager
    private lateinit var permissionStateNotifier: PermissionStateNotifier
    private lateinit var discoveryDeps: SimDiscoveryDependencies

    private val dataStore: DataStore<Preferences> = mock()
    private val seedRepository: SimIdentitySeedRepository = mock()
    private val appSettingsRepository: AppSettingsRepository = mock()
    private val migrationService: IdentityMigrationService = mock()
    private val mockSimProvider: MockSimProvider = mock()
    private val timeProvider: TimeProvider = mock()

    @Before
    fun setup() {
        context = mock()
        subscriptionManager = mock()
        permissionManager = mock()
        permissionStateNotifier = mock()

        whenever(context.mainExecutor).thenReturn(Executor { it.run() })

        discoveryDeps =
            SimDiscoveryDependencies(
                context = context,
                subscriptionManager = subscriptionManager,
                permissionManager = permissionManager,
                permissionStateNotifier = permissionStateNotifier,
            )

        whenever(appSettingsRepository.observeAppSettings()).thenReturn(MutableStateFlow(AppSettings()))
        whenever(seedRepository.seedFlow).thenReturn(flowOf("test_seed"))
        seedRepository.stub {
            on { getOrCreateSeedOrEphemeralFallback(any()) } doReturn SeedResolutionResult.Persistent("test_seed")
        }
        whenever(permissionStateNotifier.changes).thenReturn(flowOf(Unit))
        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
        whenever(permissionManager.getActiveSubscriptions()).thenReturn(emptyList())
    }

    @Test
    @Config(sdk = [30]) // behavior-specific SDK
    // @Suppress("DEPRECATION") is required because test checks legacy subscription listener registration on API 29/30.
    @Suppress("DEPRECATION")
    fun `addOnSubscriptionsChangedListener uses executor on API 30`() =
        runTest {
            val repository = createRepository(backgroundScope)

            val job =
                launch {
                    repository.observeActiveSims().collect {}
                }
            advanceUntilIdle()
            runCurrent()
            advanceUntilIdle()

            verify(subscriptionManager).addOnSubscriptionsChangedListener(any<Executor>(), any())
            verify(subscriptionManager, never())
                .addOnSubscriptionsChangedListener(any<SubscriptionManager.OnSubscriptionsChangedListener>())

            job.cancel()
        }

    @Test
    @Config(sdk = [29]) // behavior-specific SDK
    // @Suppress("DEPRECATION") is required because test checks legacy subscription listener registration on API 29.
    @Suppress("DEPRECATION")
    fun `addOnSubscriptionsChangedListener uses legacy on API 29`() =
        runTest {
            val repository = createRepository(backgroundScope)

            val job =
                launch {
                    repository.observeActiveSims().collect {}
                }
            advanceUntilIdle()
            runCurrent()
            advanceUntilIdle()

            verify(
                subscriptionManager,
            ).addOnSubscriptionsChangedListener(any<SubscriptionManager.OnSubscriptionsChangedListener>())

            job.cancel()
        }

    @Test
    fun `observeActiveSims recovers after permission regrant`() =
        runTest {
            val permissionChanges = MutableSharedFlow<Unit>(replay = 1)
            whenever(permissionStateNotifier.changes).thenReturn(permissionChanges)

            // 1. Setup discovery to throw SecurityException on first call, then succeed
            var activeSubCalls = 0
            val discoveryChanges = MutableSharedFlow<Unit>(replay = 1)
            val discovery = mock<SimIdentityDiscovery>()
            whenever(discovery.observeSubscriptionChanges()).thenReturn(discoveryChanges)

            whenever(discovery.discoverSims(any(), any())).thenAnswer {
                activeSubCalls++
                if (activeSubCalls == 1) {
                    SimDiscoveryResult.PermissionMissing
                } else {
                    SimDiscoveryResult.Available(emptyList())
                }
            }

            val persistence = SimIdentityPersistence(dataStore, migrationService)
            val cache = SimIdentityCache(timeProvider)
            val fetcher = ActiveSimsFetcher(discovery, persistence, cache)
            val observer =
                SimIdentityObserver(
                    discovery = discovery,
                    seedRepository = seedRepository,
                    appSettingsRepository = appSettingsRepository,
                    mockSimProvider = mockSimProvider,
                    fetcher = fetcher,
                    migrationService = migrationService,
                    scope = backgroundScope,
                    cache = cache,
                )
            val resolver =
                SimIdentityResolver(
                    discovery = discovery,
                    persistence = persistence,
                    cache = cache,
                )
            val repository = SimIdentityRepositoryImpl(observer, resolver, persistence)

            val emissions = mutableListOf<List<host.serv.djbigmuh.minutewatcher.core.model.ActiveSimInfo>>()
            val job =
                launch {
                    repository.observeActiveSims().collect { emissions.add(it) }
                }

            // 2. Wait for initial failure and suspension
            discoveryChanges.emit(Unit)
            advanceTimeBy(1000.milliseconds)
            advanceUntilIdle()
            runCurrent()
            assertEquals(1, emissions.size)
            assertTrue(emissions[0].isEmpty())
            assertEquals(1, activeSubCalls)

            // 3. Trigger permission regrant signal
            discoveryChanges.emit(Unit)
            // Advance time to pass debounce (100ms)
            advanceTimeBy(1000.milliseconds)
            advanceUntilIdle()
            runCurrent()

            // 4. Verify recovery
            assertTrue("Should have retried and stayed active, actual calls: $activeSubCalls", activeSubCalls >= 2)

            job.cancel()
        }

    private fun createRepository(scope: CoroutineScope) =
        SimIdentityRepositoryTestHelper.createRepository(
            discoveryDeps = discoveryDeps,
            dataStore = dataStore,
            seedRepository = seedRepository,
            appSettingsRepository = appSettingsRepository,
            migrationService = migrationService,
            mockSimProvider = mockSimProvider,
            timeProvider = timeProvider,
            scope = scope,
        )
}
