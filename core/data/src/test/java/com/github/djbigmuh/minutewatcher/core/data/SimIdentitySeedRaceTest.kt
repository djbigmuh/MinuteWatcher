package com.github.djbigmuh.minutewatcher.core.data

import android.content.Context
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.github.djbigmuh.minutewatcher.core.common.util.FixedTimeProvider
import com.github.djbigmuh.minutewatcher.core.common.util.PermissionManager
import com.github.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import com.github.djbigmuh.minutewatcher.core.domain.PermissionStateNotifier
import com.github.djbigmuh.minutewatcher.core.domain.SeedResolutionResult
import com.github.djbigmuh.minutewatcher.core.domain.SimIdentitySeedRepository
import com.github.djbigmuh.minutewatcher.core.model.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.milliseconds

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SimIdentitySeedRaceTest {
    private lateinit var context: Context
    private lateinit var subscriptionManager: SubscriptionManager
    private lateinit var permissionManager: PermissionManager
    private lateinit var permissionStateNotifier: PermissionStateNotifier
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var seedRepository: SimIdentitySeedRepository
    private val timeProvider =
        FixedTimeProvider(currentTime = 1000000L)
    private lateinit var appSettingsRepository: AppSettingsRepository
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
        permissionManager = mock()
        permissionStateNotifier = mock()
        migrationService = mock()
        dataStore =
            mock {
                on { data } doReturn flowOf(mock())
            }
        appSettingsRepository =
            mock {
                on { observeAppSettings() } doReturn MutableStateFlow(AppSettings())
            }
        seedRepository = mock()
        whenever(permissionStateNotifier.changes).thenReturn(flowOf(Unit))
        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)

        discoveryDeps =
            SimDiscoveryDependencies(
                context = context,
                subscriptionManager = subscriptionManager,
                permissionManager = permissionManager,
                permissionStateNotifier = permissionStateNotifier,
            )
    }

    @Test
    fun `identities are not computed until seed is available`() =
        runTest {
            val seedFlow = MutableSharedFlow<String>(replay = 1).apply { tryEmit("") }
            whenever(seedRepository.seedFlow).thenReturn(seedFlow)
            // Mock getOrCreateSeedOrEphemeralFallback to hang so it doesn't auto-generate seed during timeout
            whenever(seedRepository.getOrCreateSeedOrEphemeralFallback(any())).thenAnswer {
                SeedResolutionResult.Persistent("generated")
            }

            val repository =
                SimIdentityRepositoryTestHelper.createRepository(
                    discoveryDeps = discoveryDeps,
                    dataStore = dataStore,
                    seedRepository = seedRepository,
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    timeProvider = timeProvider,
                    scope = backgroundScope,
                )

            val results = mutableListOf<List<String>>()
            backgroundScope.launch {
                repository.getActiveIdentities().collect { results.add(it) }
            }

            // Initially seed is empty/not emitted
            assertTrue("Should not emit with empty seed", results.isEmpty())

            // Provide seed
            seedFlow.emit("test-seed")

            // Wait for debounce (300ms)
            advanceTimeBy(400.milliseconds)

            assertTrue("Should emit after seed is available", results.isNotEmpty())
        }

    @Test
    fun `identity remains stable after seed creation`() =
        runTest {
            val seedFlow = MutableSharedFlow<String>(replay = 1).apply { tryEmit("") }
            whenever(seedRepository.seedFlow).thenReturn(seedFlow)
            whenever(seedRepository.getOrCreateSeedOrEphemeralFallback(any())).doReturn(
                SeedResolutionResult.Persistent(
                    "test-seed",
                ),
            )

            val mockSubInfo = mock<SubscriptionInfo>()
            whenever(mockSubInfo.subscriptionId).thenReturn(1)
            whenever(mockSubInfo.simSlotIndex).thenReturn(0)
            whenever(mockSubInfo.iccId).thenReturn("icc_1")
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(mockSubInfo))

            val repository =
                SimIdentityRepositoryTestHelper.createRepository(
                    discoveryDeps = discoveryDeps,
                    dataStore = dataStore,
                    seedRepository = seedRepository,
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    timeProvider = timeProvider,
                    scope = backgroundScope,
                )

            // Wait for the initialization to complete deterministically
            testScheduler.advanceUntilIdle()

            // Provide seed
            seedFlow.emit("test-seed")
            testScheduler.runCurrent()

            val firstSeed = repository.getSimIdentity(0).stableId

            // Second resolution attempt. It should still work and return same value.
            val secondSeed = repository.getSimIdentity(0).stableId
            assertEquals("Identity should be stable", firstSeed, secondSeed)
        }
}
