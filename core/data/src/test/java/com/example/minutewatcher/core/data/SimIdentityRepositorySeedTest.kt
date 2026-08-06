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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
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
    private val mockSimProvider: MockSimProvider = mock {
        on { getMockSims(any()) }.thenReturn(null)
    }

    @Before
    fun setup() {
        context = mock()
        subscriptionManager = mock()
        permissionStateNotifier = mock()
        permissionManager = mock()
        migrationService = mock()
        appSettingsRepository = mock {
            on { observeAppSettings() } doReturn MutableStateFlow(AppSettings())
        }
        whenever(permissionStateNotifier.changes).doReturn(MutableSharedFlow())
        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
        whenever(permissionManager.getActiveSubscriptions()).thenReturn(emptyList())

        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { tmpFolder.newFile("test_sim_settings.preferences_pb") },
        )
    }

    @Test
    fun `seedFlow recovers from fallback when seed eventually becomes available`() = runTest {
        val seedFlow = MutableStateFlow("")
        val seedRepo = object : SimIdentitySeedRepository {
            override fun getSeed(): Flow<String> = seedFlow

            override suspend fun setSeed(seed: String) {
                seedFlow.value = seed
            }

            override suspend fun getOrCreateSeed(generator: () -> String): String {
                // Return whatever is in seedFlow, but simulation timeout if empty
                if (seedFlow.value.isEmpty()) {
                    delay(1000000) // Simulating timeout
                }
                return seedFlow.value
            }
        }

        val identityRepo = SimIdentityRepositoryImpl(
            context,
            subscriptionManager,
            permissionManager,
            permissionStateNotifier,
            dataStore,
            seedRepo,
            appSettingsRepository,
            migrationService,
            mockSimProvider,
            backgroundScope,
        )

        val identitiesList = mutableListOf<List<String>>()
        val identitiesJob = launch {
            identityRepo.getActiveIdentities().collect {
                identitiesList.add(it)
            }
        }

        advanceUntilIdle() // This should trigger the timeout and emission of FALLBACK_SEED

        // At this point, seedFlow should have emitted FALLBACK_SEED.
        // getActiveIdentities() should return names based on slot indices (sim_0, sim_1 etc if they exist)
        // Let's mock a subscription to see it in action.
        val activeSub = mock<android.telephony.SubscriptionInfo> {
            on { simSlotIndex } doReturn 0
            on { subscriptionId } doReturn 1
            on { displayName } doReturn "SIM 1"
        }
        whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(activeSub))

        // Trigger a refresh of activeSims (usually happens on permission change or listener)
        // In our case, physicalActiveSims is a flatMapLatest of seedFlow.
        // So when seedFlow emits FALLBACK_SEED, physicalActiveSims should emit.

        advanceUntilIdle()

        // After timeout, it should have used FALLBACK_SEED
        // FALLBACK_SEED causes identity to be "sim_0"
        assertTrue(identitiesList.any { it.contains("sim_0") })

        // Now simulate seed becoming available
        seedFlow.value = "real_seed"
        advanceUntilIdle()

        // It should now have emitted identities based on real_seed.
        // real_seed identity for slot 0 should NOT be "sim_0" (usually it's a hash or UUID)
        assertTrue(identitiesList.any { it.isNotEmpty() && !it.contains("sim_0") })

        identitiesJob.cancel()
    }
}
