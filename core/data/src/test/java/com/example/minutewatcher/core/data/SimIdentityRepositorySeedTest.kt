package com.example.minutewatcher.core.data

import android.content.Context
import android.telephony.SubscriptionInfo
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
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SimIdentityRepositorySeedTest {
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

    private val permissionChanges = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }

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
        whenever(permissionStateNotifier.changes).doReturn(permissionChanges)
        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
        whenever(permissionManager.getActiveSubscriptions()).thenReturn(emptyList())

        discoveryDeps =
            SimDiscoveryDependencies(
                context = context,
                subscriptionManager = subscriptionManager,
                permissionManager = permissionManager,
                permissionStateNotifier = permissionStateNotifier,
            )
    }

    @Test
    fun `seedFlow recovers from fallback when seed eventually becomes available`() =
        runTest {
            dataStore =
                PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { tmpFolder.newFile("test_sim_settings.preferences_pb") },
                )

            // Mock a subscription info beforehand
            val mockSubInfo = mock<SubscriptionInfo>()
            whenever(mockSubInfo.subscriptionId).thenReturn(1)
            whenever(mockSubInfo.simSlotIndex).thenReturn(0)
            whenever(mockSubInfo.iccId).thenReturn("icc_1")
            whenever(mockSubInfo.displayName).thenReturn("SIM 1")
            whenever(mockSubInfo.carrierName).thenReturn("Carrier 1")
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(mockSubInfo))

            val storedSeed = MutableSharedFlow<String>(replay = 1).apply { tryEmit("") }
            val seedRepo =
                object : SimIdentitySeedRepository {
                    override val seedFlow: Flow<String> = storedSeed

                    override suspend fun setSeed(seed: String) {
                        storedSeed.emit(seed)
                    }

                    override suspend fun getOrCreateSeed(generator: () -> String): String {
                        val current = storedSeed.replayCache.firstOrNull() ?: ""
                        if (current.isEmpty()) {
                            delay(60.seconds)
                        }
                        return current.ifEmpty { generator() }
                    }
                }

            val identityRepo =
                SimIdentityRepositoryImpl(
                    discoveryDeps = discoveryDeps,
                    dataStore = dataStore,
                    seedRepository = seedRepo,
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    scope = backgroundScope,
                )

            // Ждём, пока seedFlow перейдет в Fallback естественным путем (2 попытки по 10с + задержка)
            testScheduler.advanceTimeBy(25_000)
            testScheduler.advanceUntilIdle()

            // Проверяем fallback состояние через прямой вызов
            val fallbackIdentity = identityRepo.getSimIdentity(0)
            assertTrue(
                "Expected fallback identity, got: ${fallbackIdentity.stableId}",
                fallbackIdentity.stableId.startsWith("sim_"),
            )
            assertEquals(IdentitySource.SLOT_FALLBACK, fallbackIdentity.source)

            // Теперь предоставляем реальный сид
            storedSeed.emit("real_seed")
            // Даем время на распространение сигнала по цепочке Flow и обновление кэша (stateIn)
            testScheduler.advanceTimeBy(1_000)
            testScheduler.advanceUntilIdle()

            // Проверяем восстановление
            val realIdentity = identityRepo.getSimIdentity(0)
            assertTrue(
                "Expected identity based on real_seed, got: ${realIdentity.stableId}",
                realIdentity.stableId.startsWith("icc_"),
            )
            assertEquals(IdentitySource.ICCID, realIdentity.source)
        }

    @Test
    fun `getSimIdentity uses fallback cache and clears it on real seed`() =
        runTest {
            dataStore =
                PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { tmpFolder.newFile("test_sim_settings_cache.preferences_pb") },
                )

            val storedSeed = MutableSharedFlow<String>(replay = 1).apply { tryEmit("") }
            val seedRepo =
                object : SimIdentitySeedRepository {
                    override val seedFlow: Flow<String> = storedSeed

                    override suspend fun setSeed(seed: String) = storedSeed.emit(seed)

                    override suspend fun getOrCreateSeed(generator: () -> String): String {
                        delay(60.seconds)
                        return "generated"
                    }
                }

            val identityRepo =
                SimIdentityRepositoryImpl(
                    discoveryDeps = discoveryDeps,
                    dataStore = dataStore,
                    seedRepository = seedRepo,
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    scope = backgroundScope,
                )

            // 1. Advance to Fallback state
            testScheduler.advanceTimeBy(25_000)
            testScheduler.advanceUntilIdle()

            // 2. First call - populates cache
            val id1 = identityRepo.getSimIdentity(0)
            assertEquals("sim_0", id1.stableId)

            // 3. Second call - must be from cache (no suspension for activeSimsCache)
            // We verify by advancing time very little - if it's cached, it returns immediately.
            // If it weren't cached, it would wait for activeSimsCache.firstWithTimeout(...)
            val id2 = identityRepo.getSimIdentity(0)
            assertEquals("sim_0", id2.stableId)

            // 4. Transition to Real seed
            storedSeed.emit("real_seed")
            testScheduler.advanceTimeBy(1000)
            testScheduler.advanceUntilIdle()

            // 5. Cache should be cleared, call should return real identity (based on ICCID if we provide mock sub)
            val mockSubInfo = mock<SubscriptionInfo>()
            whenever(mockSubInfo.subscriptionId).thenReturn(1)
            whenever(mockSubInfo.simSlotIndex).thenReturn(0)
            whenever(mockSubInfo.iccId).thenReturn("icc_1")
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(mockSubInfo))

            val id3 = identityRepo.getSimIdentity(0)
            assertTrue("Should return real ID now, got ${id3.stableId}", id3.stableId.startsWith("icc_"))
        }

    @Test
    fun `migrationIsCalledWhenTransitioningFromFallbackToRealSeed`() =
        runTest {
            dataStore =
                PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { tmpFolder.newFile("test_sim_migration.preferences_pb") },
                )

            val storedSeed = MutableSharedFlow<String>(replay = 1).apply { tryEmit("") }
            val seedRepo =
                object : SimIdentitySeedRepository {
                    override val seedFlow: Flow<String> = storedSeed

                    override suspend fun setSeed(seed: String) = storedSeed.emit(seed)

                    override suspend fun getOrCreateSeed(generator: () -> String): String {
                        delay(60.seconds)
                        return "generated"
                    }
                }

            val identityRepo =
                SimIdentityRepositoryImpl(
                    discoveryDeps = discoveryDeps,
                    dataStore = dataStore,
                    seedRepository = seedRepo,
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    scope = backgroundScope,
                )

            // 1. Mock a subscription info
            val mockSubInfo = mock<SubscriptionInfo>()
            whenever(mockSubInfo.subscriptionId).thenReturn(1)
            whenever(mockSubInfo.simSlotIndex).thenReturn(0)
            whenever(mockSubInfo.iccId).thenReturn("icc_1")
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(mockSubInfo))

            // 2. Advance to Fallback state and trigger cache population
            testScheduler.advanceTimeBy(25_000)
            testScheduler.advanceUntilIdle()
            identityRepo.getSimIdentity(0) // Now sim_0 is in fallbackCache

            // 3. Transition to Real seed
            storedSeed.emit("real_seed")
            testScheduler.advanceTimeBy(1000)
            testScheduler.advanceUntilIdle()

            // 4. Verify migration was called for the new identity
            org.mockito.kotlin.verify(migrationService).migrateLegacyData(
                org.mockito.kotlin.check { identity ->
                    assertEquals(0, identity.slotIndex)
                    assertTrue(identity.stableId.startsWith("icc_"))
                    assertEquals(IdentitySource.ICCID, identity.source)
                },
            )
        }
}
