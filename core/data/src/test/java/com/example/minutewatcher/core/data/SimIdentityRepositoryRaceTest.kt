package com.example.minutewatcher.core.data

import android.content.Context
import android.telephony.SubscriptionManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.model.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SimIdentityRepositoryRaceTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var subscriptionManager: SubscriptionManager
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var permissionStateNotifier: PermissionStateNotifier
    private lateinit var appSettingsRepository: AppSettingsRepository
    private lateinit var permissionManager: PermissionManager
    private lateinit var migrationService: IdentityMigrationService
    private lateinit var discoveryDeps: SimDiscoveryDependencies
    private val mockSimProvider: MockSimProvider =
        mock {
            on { getMockSims(any()) }.thenReturn(null)
        }

    @Before
    fun setup() {
        context = mock()
        subscriptionManager = mock()
        permissionStateNotifier = mock()
        permissionManager = mock()
        migrationService = mock()
        appSettingsRepository =
            mock {
                on { observeAppSettings() } doReturn MutableStateFlow(AppSettings())
            }
        whenever(permissionStateNotifier.changes).doReturn(MutableSharedFlow())
        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
        whenever(permissionManager.getActiveSubscriptions()).thenReturn(emptyList())

        dataStore =
            PreferenceDataStoreFactory.create(
                produceFile = { tmpFolder.newFile("test_sim_settings.preferences_pb") },
            )

        discoveryDeps =
            SimDiscoveryDependencies(
                context = context,
                subscriptionManager = subscriptionManager,
                permissionManager = permissionManager,
                permissionStateNotifier = permissionStateNotifier,
            )
    }

    @Test
    fun `seedFlow emits Fallback on timeout and then Real when seed arrives`() =
        runTest {
            val storedSeed = MutableSharedFlow<String>(replay = 0)
            val seedRepo =
                object : SimIdentitySeedRepository {
                    override val seedFlow: Flow<String> = storedSeed

                    override suspend fun setSeed(seed: String) = Unit

                    override suspend fun getOrCreateSeed(generator: () -> String): String = "generated"
                }

            val identityRepo =
                SimIdentityRepositoryImpl(
                    discoveryDeps = discoveryDeps,
                    dataStore = dataStore,
                    seedRepository = seedRepo,
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    timeProvider = mock(),
                    scope = backgroundScope,
                )

            // Access seedFlow via reflection to test its emissions directly
            val seedFlowField = SimIdentityRepositoryImpl::class.java.getDeclaredField("seedFlow")
            seedFlowField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val seedFlow = seedFlowField.get(identityRepo) as Flow<Any>

            val emissions = mutableListOf<Any>()
            val job =
                launch {
                    seedFlow.collect { emissions.add(it) }
                }

            // 1. Initial state: seedFlow emitted "", collect starts.
            // 2. Wait 13 seconds for timeout.
            testScheduler.advanceTimeBy(13.seconds)
            testScheduler.runCurrent()

            // Should have Fallback
            assertTrue(
                "Emissions should contain Fallback, found: $emissions",
                emissions.any { it.toString().contains("Fallback") },
            )
            assertEquals(1, emissions.size)

            // 3. Emit real seed
            storedSeed.emit("real_seed")
            testScheduler.advanceTimeBy(1.seconds)
            testScheduler.runCurrent()

            // Should have Real("real_seed")
            assertTrue(
                "Emissions should contain Real(real_seed), found: $emissions",
                emissions.any { it.toString().contains("real_seed") },
            )
            assertEquals(2, emissions.size)

            // 4. Wait more time to ensure no extra Fallback is emitted
            testScheduler.advanceTimeBy(10.seconds)
            testScheduler.runCurrent()

            assertEquals("Should not have extra emissions", 2, emissions.size)

            job.cancel()
        }

    @Test
    fun `seedFlow does not emit Fallback if Real arrives before timeout`() =
        runTest {
            val storedSeed = MutableSharedFlow<String>(replay = 0)
            val seedRepo =
                object : SimIdentitySeedRepository {
                    override val seedFlow: Flow<String> = storedSeed

                    override suspend fun setSeed(seed: String) = Unit

                    override suspend fun getOrCreateSeed(generator: () -> String): String = "gen"
                }

            val identityRepo =
                SimIdentityRepositoryImpl(
                    discoveryDeps =
                        SimDiscoveryDependencies(
                            context = mock(),
                            subscriptionManager = mock(),
                            permissionManager = mock(),
                            permissionStateNotifier =
                                mock {
                                    on { changes } doReturn MutableSharedFlow()
                                },
                        ),
                    dataStore = dataStore,
                    seedRepository = seedRepo,
                    appSettingsRepository =
                        mock {
                            on { observeAppSettings() } doReturn MutableStateFlow(AppSettings())
                        },
                    migrationService = mock(),
                    mockSimProvider =
                        mock {
                            on { getMockSims(any()) } doReturn null
                        },
                    timeProvider = mock(),
                    scope = backgroundScope,
                )

            val seedFlowField = SimIdentityRepositoryImpl::class.java.getDeclaredField("seedFlow")
            seedFlowField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val seedFlow = seedFlowField.get(identityRepo) as Flow<Any>

            val emissions = mutableListOf<Any>()
            val job =
                launch {
                    seedFlow.collect { emissions.add(it) }
                }

            // 1. Emit real seed at T=5s
            testScheduler.advanceTimeBy(5.seconds)
            storedSeed.emit("real_seed")
            testScheduler.runCurrent()

            assertEquals(1, emissions.size)
            assertTrue(emissions[0].toString().contains("real_seed"))

            // 2. Wait past 13s timeout
            testScheduler.advanceTimeBy(10.seconds)
            testScheduler.runCurrent()

            // Should STILL have only 1 emission (Real), no Fallback
            assertEquals("Should not emit Fallback after Real", 1, emissions.size)

            job.cancel()
        }

    @Test
    fun `concurrent getSimIdentity calls are serialized by mutex`() =
        runTest {
            val seedRepo = mock<SimIdentitySeedRepository>()
            whenever(seedRepo.seedFlow).thenReturn(flowOf("test-seed"))
            whenever(seedRepo.getOrCreateSeed(any())).thenReturn("test-seed")

            val mockSubInfo = mock<android.telephony.SubscriptionInfo>()
            whenever(mockSubInfo.simSlotIndex).thenReturn(0)
            whenever(mockSubInfo.subscriptionId).thenReturn(1)
            whenever(mockSubInfo.iccId).thenReturn("icc_1")

            val permissionManager = mock<PermissionManager>()
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(mockSubInfo))

            val localDiscoveryDeps =
                SimDiscoveryDependencies(
                    context = mock(),
                    subscriptionManager = mock(),
                    permissionManager = permissionManager,
                    permissionStateNotifier = mock { on { changes } doReturn flowOf(Unit) },
                )
            val repository =
                SimIdentityRepositoryImpl(
                    discoveryDeps = localDiscoveryDeps,
                    dataStore = dataStore,
                    seedRepository = seedRepo,
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    timeProvider = mock { on { elapsedRealtime() } doReturn 1000L },
                    scope = backgroundScope,
                )

            val jobs =
                List(10) {
                    launch {
                        repository.getSimIdentity(0).stableId
                    }
                }
            jobs.joinAll()

            // Because of the mutex and caching, resolution should be stable.
            // Persistence.saveSimIdentitiesInfo should be called, which in turn calls migrationService if needed.
            // Since all 10 calls happen concurrently for the SAME slot, the mutex ensures only one resolution occurs.
            verify(migrationService, times(1)).migrateLegacyData(any())
        }
}
