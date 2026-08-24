package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.IdentitySource
import com.example.minutewatcher.core.model.SimIdentity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class SimIdentityRepositoryImplPersistenceTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val migrationService: IdentityMigrationService = mock()
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
        runTest(testDispatcher) {
            val realDataStore =
                PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { tmpFolder.newFile("test_ds.preferences_pb") },
                )
            val countingDataStore = CountingDataStore(realDataStore)
            val identity = SimIdentity("stable_id", 1, 101, IdentitySource.SUBSCRIPTION_ID)

            // 1. Initial save to populate DataStore
            val persistence = SimIdentityPersistence(countingDataStore, migrationService)
            persistence.saveSimIdentitiesInfo(listOf(identity))
            val initialCount = countingDataStore.updateCount
            assert(initialCount > 0)

            // 2. Save identical data
            persistence.saveSimIdentitiesInfo(listOf(identity))

            // Verify updateCount didn't increase
            assertEquals("Should not call edit for identical data", initialCount, countingDataStore.updateCount)
        }

    @Test
    fun `activeSimsCache emits unique values only`() =
        runTest(testDispatcher) {
            val appSettingsRepo = mock<AppSettingsRepository>()
            val appSettingsFlow = MutableStateFlow(AppSettings())
            whenever(appSettingsRepo.observeAppSettings()).thenReturn(appSettingsFlow)

            val seedRepository =
                mock<SimIdentitySeedRepository> {
                    on { seedFlow } doReturn flowOf("test_seed")
                    on { getOrCreateSeed(any()) } doReturn "test_seed"
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
                SimIdentityRepositoryImpl(
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
            for (i in 0 until results.size - 1) {
                val areEqual = results[i] == results[i + 1]
                assert(!areEqual) { "Duplicate emission at index $i: ${results[i]}" }
            }
        }

    @Test
    fun `getSimIdentity returns fresh data even without subscribers`() =
        runTest(testDispatcher) {
            val appSettingsRepo = mock<AppSettingsRepository>()
            val appSettingsFlow = MutableStateFlow(AppSettings())
            whenever(appSettingsRepo.observeAppSettings()).thenReturn(appSettingsFlow)

            val seedRepository =
                mock<SimIdentitySeedRepository> {
                    on { seedFlow } doReturn flowOf("test_seed")
                    on { getOrCreateSeed(any()) } doReturn "test_seed"
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
                SimIdentityRepositoryImpl(
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

            // 1. Initial check (no subscribers)
            val id1 = repository.getSimIdentity(0)
            assertTrue("Expected iccid_initial, got ${id1.stableId}", id1.stableId.contains("iccid_initial"))

            // 2. Change SIM info
            val subInfo2 = mock<android.telephony.SubscriptionInfo>()
            whenever(subInfo2.simSlotIndex).thenReturn(0)
            whenever(subInfo2.subscriptionId).thenReturn(101)
            whenever(subInfo2.iccId).thenReturn("iccid_updated")
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(subInfo2))

            // Trigger discovery update
            permissionChanges.emit(Unit)
            testScheduler.advanceUntilIdle()

            // 3. Check again (still no subscribers to observeActiveSims)
            val id2 = repository.getSimIdentity(0)
            assertTrue("Expected iccid_updated, got ${id2.stableId}", id2.stableId.contains("iccid_updated"))
        }
}
