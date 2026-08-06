package com.example.minutewatcher.core.data

import android.content.Context
import android.telephony.SubscriptionManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.model.AppSettings
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

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SimIdentitySeedRaceTest {
    private lateinit var context: Context
    private lateinit var subscriptionManager: SubscriptionManager
    private lateinit var permissionManager: PermissionManager
    private lateinit var permissionStateNotifier: PermissionStateNotifier
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var seedRepository: SimIdentitySeedRepository
    private lateinit var appSettingsRepository: AppSettingsRepository
    private lateinit var migrationService: IdentityMigrationService
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
        dataStore = mock {
            on { data } doReturn flowOf(mock())
        }
        appSettingsRepository = mock {
            on { observeAppSettings() } doReturn MutableStateFlow(AppSettings())
        }
        seedRepository = mock()
        whenever(permissionStateNotifier.changes).thenReturn(flowOf(Unit))
    }

    @Test
    fun `identities are not computed until seed is available`() =
        runTest {
            val seedFlow = MutableSharedFlow<String>(replay = 1)
            whenever(seedRepository.getSeed()).thenReturn(seedFlow)
            // Mock getOrCreateSeed to hang so it doesn't auto-generate seed during timeout
            whenever(seedRepository.getOrCreateSeed(any())).thenAnswer {
                "generated"
            }

            val repository =
                SimIdentityRepositoryImpl(
                    context,
                    subscriptionManager,
                    permissionManager,
                    permissionStateNotifier,
                    dataStore,
                    seedRepository,
                    appSettingsRepository,
                    migrationService,
                    mockSimProvider,
                    backgroundScope,
                )

            val results = mutableListOf<List<String>>()
            val job =
                launch {
                    repository.observeIdentities().collect { results.add(it) }
                }

            // Initially seed is empty/not emitted
            assertTrue("Should not emit with empty seed", results.isEmpty())

            // Provide seed
            seedFlow.emit("test-seed")

            // Wait for debounce (300ms)
            advanceTimeBy(400)

            assertTrue("Should emit after seed is available", results.isNotEmpty())

            job.cancel()
        }

    @Test
    fun `identity remains stable after seed creation`() =
        runTest {
            val seedFlow = MutableSharedFlow<String>(replay = 1)
            whenever(seedRepository.getSeed()).thenReturn(seedFlow)
            whenever(seedRepository.getOrCreateSeed(any())).doReturn("test-seed")

            val repository =
                SimIdentityRepositoryImpl(
                    context,
                    subscriptionManager,
                    permissionManager,
                    permissionStateNotifier,
                    dataStore,
                    seedRepository,
                    appSettingsRepository,
                    migrationService,
                    mockSimProvider,
                    backgroundScope,
                )

            // Let it initialize and generate seed
            advanceTimeBy(10000)

            val firstSeed = repository.getIdentity(0)

            // Change seed flow (simulating update in storage)
            seedFlow.emit("new-seed")
            advanceTimeBy(400)

            val secondSeed = repository.getIdentity(0)
            assertEquals("Seed should be stable for the same session", firstSeed, secondSeed)
        }
}
