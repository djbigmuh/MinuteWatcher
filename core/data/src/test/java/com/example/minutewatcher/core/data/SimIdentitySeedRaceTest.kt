package com.example.minutewatcher.core.data

import android.content.Context
import android.telephony.SubscriptionManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SimIdentitySeedRaceTest {
    private val context: Context = mock()
    private val subscriptionManager: SubscriptionManager = mock()
    private val permissionManager: PermissionManager = mock()
    private val permissionStateNotifier: PermissionStateNotifier = mock()
    private val dataStore: DataStore<Preferences> = mock()
    private val seedRepository: SimIdentitySeedRepository = mock()
    private val appSettingsRepository: AppSettingsRepository = mock()
    private val migrationService: IdentityMigrationService = mock()
    private val mockSimProvider: MockSimProvider = mock {
        on { getMockSims(any()) }.thenReturn(null)
    }

    @Before
    fun setup() {
        whenever(dataStore.data).thenReturn(flowOf(mock()))
        whenever(
            appSettingsRepository.observeAppSettings(),
        ).thenReturn(
            kotlinx.coroutines.flow.MutableStateFlow(
                com.example.minutewatcher.core.model
                    .AppSettings(),
            ),
        )
        whenever(permissionStateNotifier.changes).thenReturn(flowOf(Unit))
    }

    @Test
    fun `identities are not computed until seed is available`() =
        runTest {
            val seedFlow = MutableSharedFlow<String>(replay = 1)
            whenever(seedRepository.getSeed()).thenReturn(seedFlow)

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
            testScheduler.advanceTimeBy(400)

            assertTrue("Should emit after seed is available", results.isNotEmpty())

            job.cancel()
        }

    @Test
    fun `identity remains stable after seed creation`() =
        runTest {
            whenever(seedRepository.getSeed()).thenReturn(flowOf("stable-seed"))

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

            val id1 = repository.getIdentity(0)
            val id2 = repository.getIdentity(0)

            assertEquals("Identity should be stable", id1, id2)
        }
}
