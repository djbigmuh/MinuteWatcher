package com.example.minutewatcher.core.data

import android.content.Context
import android.telephony.SubscriptionManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.model.AppSettings
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
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

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
        whenever(permissionStateNotifier.changes).thenReturn(flowOf(Unit))
        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
        whenever(permissionManager.getActiveSubscriptions()).thenReturn(emptyList())
    }

    @Test
    @Config(sdk = [30])
    @Suppress("DEPRECATION")
    fun `test addOnSubscriptionsChangedListener uses executor on API 30`() =
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
    @Config(sdk = [29])
    @Suppress("DEPRECATION")
    fun `test addOnSubscriptionsChangedListener uses legacy on API 29`() =
        runTest {
            val repository = createRepository(backgroundScope)

            val job =
                launch {
                    repository.observeActiveSims().collect {}
                }
            advanceUntilIdle()

            job.cancel()
        }

    @Test
    fun `test observeActiveSims recovers after permission regrant`() =
        runTest {
            val permissionChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            whenever(permissionStateNotifier.changes).thenReturn(permissionChanges)

            // 1. Setup discovery to throw SecurityException on first call, then succeed
            var activeSubCalls = 0
            val discovery = mock<SimIdentityDiscovery>()
            whenever(discovery.observeSubscriptionChanges()).thenReturn(flowOf(Unit))
            whenever(discovery.discoverSims(any())).thenAnswer {
                if (activeSubCalls++ == 0) {
                    throw SecurityException("Mock permission revocation")
                } else {
                    com.example.minutewatcher.core.model.SimDiscoveryResult
                        .Available(emptyList())
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
                    scope = backgroundScope,
                    cache = cache,
                    permissionStateNotifier = permissionStateNotifier,
                )
            val resolver =
                SimIdentityResolver(
                    discovery = discovery,
                    persistence = persistence,
                    cache = cache,
                )
            val repository = SimIdentityRepositoryImpl(observer, resolver, persistence)

            val emissions = mutableListOf<List<com.example.minutewatcher.core.model.ActiveSimInfo>>()
            val job =
                launch {
                    repository.observeActiveSims().collect { emissions.add(it) }
                }

            // 2. Wait for initial failure and suspension
            advanceUntilIdle()
            assertEquals(1, emissions.size)
            assertTrue(emissions[0].isEmpty())

            // 3. Trigger permission regrant signal
            permissionChanges.emit(Unit)
            // Advance time to pass debounce (300ms) and potential backoffs
            advanceTimeBy(10000)
            advanceUntilIdle()
            runCurrent()

            // 4. Verify recovery
            assertTrue("Should have retried and stayed active", activeSubCalls >= 2)

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
