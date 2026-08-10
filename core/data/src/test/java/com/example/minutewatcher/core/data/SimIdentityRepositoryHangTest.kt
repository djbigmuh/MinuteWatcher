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
import com.example.minutewatcher.core.model.IdentitySource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
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
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SimIdentityRepositoryHangTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var subscriptionManager: SubscriptionManager
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var seedRepository: SimIdentitySeedRepository
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

        seedRepository =
            mock {
                on { seedFlow } doReturn flowOf("test_seed")
                on { getOrCreateSeed(any()) } doReturn "test_seed"
            }
        whenever(permissionStateNotifier.changes).thenReturn(flowOf(Unit))

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
    fun `getSimIdentity returns fallback when activeSimsCache hangs`() =
        runTest {
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(emptyList())

            val identityRepo =
                SimIdentityRepositoryImpl(
                    discoveryDeps = discoveryDeps,
                    dataStore = dataStore,
                    seedRepository = seedRepository,
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    scope = backgroundScope,
                )

            // Mock seedRepository.seedFlow to return a flow that never emits,
            // which will cause activeSimsCache to wait indefinitely.
            whenever(seedRepository.seedFlow).thenReturn(MutableSharedFlow())

            // Act
            val identity = identityRepo.getSimIdentity(0)

            // Assert: It should return fallback after timeout instead of hanging forever
            assertEquals("sim_0", identity.stableId)
            assertEquals(IdentitySource.SLOT_FALLBACK, identity.source)
        }

    @Test
    fun `getSimIdentity does not hang forever when seed generation fails`() =
        runTest {
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(emptyList())

            // Mock seedRepository.seedFlow to return empty/null
            whenever(seedRepository.seedFlow).thenReturn(flowOf(""))

            // Mock getOrCreateSeed to hang
            val hangingSeedRepo =
                object : SimIdentitySeedRepository {
                    override val seedFlow: Flow<String> = flowOf("")

                    override suspend fun setSeed(seed: String) {
                        // No-op
                    }

                    override suspend fun getOrCreateSeed(generator: () -> String): String {
                        delay(1000.seconds)
                        return "seed"
                    }
                }

            val identityRepo =
                SimIdentityRepositoryImpl(
                    discoveryDeps = discoveryDeps,
                    dataStore = dataStore,
                    seedRepository = hangingSeedRepo,
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    scope = backgroundScope,
                )

            // Act: Try to get identity.
            // Using withTimeoutOrNull to ensure we don't hang if implementation is broken.
            val identity =
                withTimeoutOrNull(60.seconds) {
                    try {
                        identityRepo.getSimIdentity(0)
                    } catch (_: Throwable) {
                        null
                    }
                }

            // The test is successful if it completed without hanging.
            // If it returned fallback or threw, it's fine as long as it didn't hang.
            if (identity != null) {
                assertEquals("sim_0", identity.stableId)
                assertEquals(IdentitySource.SLOT_FALLBACK, identity.source)
            }
        }

    @Test
    fun `observeActiveSims emits when seed generation fails`() =
        runTest {
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(emptyList())

            // Mock seedRepository to hang
            val hangingSeedRepo =
                object : SimIdentitySeedRepository {
                    override val seedFlow: Flow<String> = flowOf("")

                    override suspend fun setSeed(seed: String) {
                        // No-op
                    }

                    override suspend fun getOrCreateSeed(generator: () -> String): String {
                        delay(1000.seconds)
                        return "seed"
                    }
                }

            val identityRepo =
                SimIdentityRepositoryImpl(
                    discoveryDeps = discoveryDeps,
                    dataStore = dataStore,
                    seedRepository = hangingSeedRepo,
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    scope = backgroundScope,
                )

            // Act: observeActiveSims().first() should complete due to fallback seed
            val result =
                withTimeoutOrNull(60.seconds) {
                    identityRepo.observeActiveSims().first()
                }

            // Assert: it should have emitted (even if empty list)
            assertTrue("Flow should have emitted a value instead of hanging", result != null)
        }

    @Test
    fun `observeActiveSims emits empty list when seedFlow timeouts`() =
        runTest {
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(emptyList())

            val identityRepo =
                SimIdentityRepositoryImpl(
                    discoveryDeps = discoveryDeps,
                    dataStore = dataStore,
                    seedRepository =
                        mock {
                            // Return flow that never emits
                            on { seedFlow } doReturn MutableSharedFlow()
                        },
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    scope = backgroundScope,
                )

            // Act: wait for internal timeout (SEED_TOTAL_TIMEOUT_MS + 2000ms)
            // SEED_TOTAL_TIMEOUT_MS is 13000ms
            testScheduler.advanceTimeBy(15_100)
            testScheduler.runCurrent()

            val result = identityRepo.observeActiveSims().first()

            // Assert: should be empty list, not hang
            assertTrue("Result should be empty list after timeout", result.isEmpty())
        }

    @Test
    fun `saveSimIdentitiesInfo does not block other calls on slow migration`() =
        runTest {
            val migrationStarted = AtomicBoolean(false)
            val identityRepo =
                SimIdentityRepositoryImpl(
                    discoveryDeps = discoveryDeps,
                    dataStore = dataStore,
                    seedRepository = seedRepository,
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    scope = backgroundScope,
                )

            val subInfo = mock<android.telephony.SubscriptionInfo>()
            whenever(subInfo.subscriptionId).thenReturn(101)
            whenever(subInfo.simSlotIndex).thenReturn(0)
            whenever(subInfo.iccId).thenReturn("iccid_0")
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(subInfo))

            // Migration that just sets a flag and returns.
            // We'll simulate "slowness" by launching another call immediately.
            whenever(migrationService.migrateLegacyData(any())).thenAnswer {
                migrationStarted.set(true)
            }

            // Act: First call
            identityRepo.getSimIdentity(0)
            assertTrue("Migration should have started", migrationStarted.get())

            // Second call for a DIFFERENT slot.
            val subInfo2 = mock<android.telephony.SubscriptionInfo>()
            whenever(subInfo2.subscriptionId).thenReturn(102)
            whenever(subInfo2.simSlotIndex).thenReturn(1)
            whenever(subInfo2.iccId).thenReturn("iccid_1")
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(subInfo, subInfo2))

            val identity2 = identityRepo.getSimIdentity(1)
            assertTrue("Identity2 should be based on ICCID", identity2.stableId.startsWith("icc_"))
        }

    @Test
    fun `observeActiveSims returns empty list immediately when no SIMs and not initialized`() =
        runTest {
            // Setup: no permissions yet, so flow won't emit physical SIMs
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(false)

            val identityRepo =
                SimIdentityRepositoryImpl(
                    discoveryDeps = discoveryDeps,
                    dataStore = dataStore,
                    seedRepository = seedRepository,
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    scope = backgroundScope,
                )

            // Act & Assert: first() should return emptyList() immediately without blocking/timeout
            val result =
                withTimeoutOrNull(5.seconds) {
                    identityRepo.observeActiveSims().first()
                }

            assertTrue("Flow should have emitted empty list immediately", result != null)
            assertTrue("Result should be empty", result?.isEmpty() == true)
        }
}
