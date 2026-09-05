package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.data.di.DataStoreKeys
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.domain.SeedResolutionResult
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.IdentitySource
import com.example.minutewatcher.core.model.SimIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.milliseconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class SimIdentityRepositoryImplPersistenceTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmpFolder = TemporaryFolder()
    private val migrationService: IdentityMigrationService =
        mock {
            on { migrateLegacyData(any()) } doReturn IdentityMigrationService.MigrationOutcome()
        }
    private val timeProvider: TimeProvider =
        mock {
            on { elapsedRealtime() } doReturn 1000L
        }

    private class CountingDataStore(
        private val delegate: DataStore<Preferences>,
    ) : DataStore<Preferences> by delegate {
        var updateCount = 0

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            updateCount++
            return delegate.updateData(transform)
        }
    }

    @Test
    fun `saveSimIdentitiesInfo does not call edit when data is identical`() =
        runTest {
            val realDataStore =
                PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                ) {
                    tmpFolder.newFile("test_ds_2.preferences_pb")
                }
            val countingDataStore = CountingDataStore(realDataStore)
            val identity = SimIdentity("stable_id", 1, 101, IdentitySource.SUBSCRIPTION_ID, isStable = false)

            // 1. Initial save to populate DataStore
            val persistence = SimIdentityPersistence(countingDataStore, migrationService)
            persistence.saveSimIdentitiesInfo(listOf(identity))
            advanceUntilIdle()
            val initialCount = countingDataStore.updateCount
            assertTrue("Initial count should be > 0, was $initialCount", initialCount > 0)

            // 2. Save identical data
            persistence.saveSimIdentitiesInfo(listOf(identity))
            advanceUntilIdle()

            // Verify updateCount didn't increase
            assertEquals("Should not call edit for identical data", initialCount, countingDataStore.updateCount)
        }

    @Test
    fun `activeSimsCache emits unique values only`() =
        runTest {
            val appSettingsRepo = mock<AppSettingsRepository>()
            val appSettingsFlow = MutableStateFlow(AppSettings())
            whenever(appSettingsRepo.observeAppSettings()).thenReturn(appSettingsFlow)

            val seedRepository =
                mock<SimIdentitySeedRepository> {
                    on { seedFlow } doReturn flowOf("test_seed")
                    on { getOrCreateSeedOrEphemeralFallback(any()) } doReturn
                        SeedResolutionResult.Persistent("test_seed")
                }

            val permissionManager =
                mock<PermissionManager> {
                    on { hasPhoneStatePermission() } doReturn true
                }
            val permissionStateNotifier =
                mock<PermissionStateNotifier> {
                    on { changes } doReturn flowOf(Unit)
                }

            val discoveryDeps =
                SimDiscoveryDependencies(
                    context = mock(),
                    subscriptionManager = mock(),
                    permissionManager = permissionManager,
                    permissionStateNotifier = permissionStateNotifier,
                )

            val mockSimProvider =
                mock<MockSimProvider> {
                    on { getMockSims(any()) } doReturn null
                }

            val repository =
                SimIdentityRepositoryTestHelper.createRepository(
                    discoveryDeps = discoveryDeps,
                    dataStore =
                        mock<DataStore<Preferences>> {
                            on { data } doReturn flowOf(mock<Preferences>())
                        },
                    seedRepository = seedRepository,
                    appSettingsRepository = appSettingsRepo,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    timeProvider = timeProvider,
                    scope = backgroundScope,
                )

            val results = mutableListOf<List<com.example.minutewatcher.core.model.ActiveSimInfo>>()
            val job =
                launch {
                    repository.observeActiveSims().toList(results)
                }

            // Trigger multiple setting updates with the SAME value
            appSettingsFlow.emit(AppSettings(benchmarkSimCount = 0))
            appSettingsFlow.emit(AppSettings(benchmarkSimCount = 0))

            job.cancel()

            // Check for duplicates
            for (i in 0 until (results.size - 1)) {
                val areEqual = results[i] == results[i + 1]
                assert(!areEqual) { "Duplicate emission at index $i: ${results[i]}" }
            }
        }

    @Test
    @Suppress("LongMethod")
    fun `getSimIdentity returns fresh data when subscribed`() =
        runTest {
            val appSettingsRepo = mock<AppSettingsRepository>()
            val appSettingsFlow = MutableStateFlow(AppSettings())
            whenever(appSettingsRepo.observeAppSettings()).thenReturn(appSettingsFlow)

            val seedRepository =
                mock<SimIdentitySeedRepository> {
                    on { seedFlow } doReturn flowOf("test_seed")
                    on { getOrCreateSeedOrEphemeralFallback(any()) } doReturn
                        SeedResolutionResult.Persistent("test_seed")
                }

            val permissionChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            val permissionManager =
                mock<PermissionManager> {
                    on { hasPhoneStatePermission() } doReturn true
                }
            val permissionStateNotifier =
                mock<PermissionStateNotifier> {
                    on { changes } doReturn permissionChanges
                }

            val subscriptionManager = mock<android.telephony.SubscriptionManager>()

            val discoveryDeps =
                SimDiscoveryDependencies(
                    context = mock(),
                    subscriptionManager = subscriptionManager,
                    permissionManager = permissionManager,
                    permissionStateNotifier = permissionStateNotifier,
                )

            val mockSimProvider =
                mock<MockSimProvider> {
                    on { getMockSims(any()) } doReturn null
                }

            // SIM info for slot 0
            val subInfo = mock<android.telephony.SubscriptionInfo>()
            whenever(subInfo.simSlotIndex).thenReturn(0)
            whenever(subInfo.subscriptionId).thenReturn(101)
            whenever(subInfo.iccId).thenReturn("iccid_initial")

            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(subInfo))

            val repository =
                SimIdentityRepositoryTestHelper.createRepository(
                    discoveryDeps = discoveryDeps,
                    dataStore =
                        mock<DataStore<Preferences>> {
                            on { data } doReturn flowOf(mock<Preferences>())
                        },
                    seedRepository = seedRepository,
                    appSettingsRepository = appSettingsRepo,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    timeProvider = timeProvider,
                    scope = backgroundScope,
                )

            // Start observation to activate WhileSubscribed
            val collectJob = launch { repository.observeActiveSims().collect {} }

            // 1. Initial check
            val id1 = repository.getSimIdentity(0)
            assertTrue("Expected icc_ prefix, got ${id1.stableId}", id1.stableId.startsWith("icc_"))

            // 2. Change SIM info
            val subInfo2 = mock<android.telephony.SubscriptionInfo>()
            whenever(subInfo2.simSlotIndex).thenReturn(0)
            whenever(subInfo2.subscriptionId).thenReturn(101)
            whenever(subInfo2.iccId).thenReturn("iccid_updated")
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(subInfo2))

            // Trigger discovery update
            permissionChanges.emit(Unit)
            advanceTimeBy(1000.milliseconds) // Pass debounce
            advanceUntilIdle()

            // 3. Check again
            val id2 = repository.getSimIdentity(0)
            assertTrue("Expected updated identity, got ${id2.stableId}", id2.stableId != id1.stableId)
            assertTrue("Expected icc_ prefix, got ${id2.stableId}", id2.stableId.startsWith("icc_"))

            collectJob.cancel()
        }

    @Test
    fun `saveSimIdentitiesInfo performs migration outside saveMutex`() =
        runTest {
            val realDataStore =
                PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                ) {
                    tmpFolder.newFile("test_ds_migration_lock.preferences_pb")
                }

            val migrationStarted = CompletableDeferred<Unit>()
            val canFinishMigration = CompletableDeferred<Unit>()

            val fakeMigrationService =
                object : IdentityMigrationService(mock(), mock()) {
                    override suspend fun migrateLegacyData(activeIdentity: SimIdentity): MigrationOutcome {
                        if (activeIdentity.stableId == "to_migrate") {
                            migrationStarted.complete(Unit)
                            canFinishMigration.await()
                        }
                        return MigrationOutcome(migrated = 1)
                    }
                }

            val persistence = SimIdentityPersistence(realDataStore, fakeMigrationService)
            val identity = SimIdentity("to_migrate", 101, 0, IdentitySource.ICCID, isStable = true)

            // Start save that triggers migration
            val saveJob =
                launch {
                    persistence.saveSimIdentitiesInfo(listOf(identity))
                }

            migrationStarted.await()

            // At this point migration IS RUNNING.
            // If saveMutex is FREE, a second save can start.
            val secondSaveStarted = CompletableDeferred<Unit>()
            val secondSaveJob =
                launch {
                    // subscriptionId = 102, slotIndex = 1
                    val otherIdentity = SimIdentity("other", 102, 1, IdentitySource.ICCID, isStable = true)
                    secondSaveStarted.complete(Unit)
                    persistence.saveSimIdentitiesInfo(listOf(otherIdentity))
                }

            secondSaveStarted.await()

            // Advance time to let second save finish
            advanceUntilIdle()

            // Verify second save finished while first was still "migrating"
            val prefs = realDataStore.data.first()
            assertEquals(1, prefs[DataStoreKeys.slotIndexKey("other")])

            // Cleanup
            canFinishMigration.complete(Unit)
            saveJob.join()
            secondSaveJob.join()
        }
}
