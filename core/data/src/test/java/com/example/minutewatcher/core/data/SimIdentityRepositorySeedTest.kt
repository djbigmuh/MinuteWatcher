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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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

        dataStore =
            PreferenceDataStoreFactory.create(
                produceFile = { tmpFolder.newFile("test_sim_settings.preferences_pb") },
            )
    }

    @Test
    fun `seedFlow recovers from fallback when seed eventually becomes available`() =
        runTest {
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
            testScheduler.advanceUntilIdle()

            val identitiesList = mutableListOf<List<String>>()
            val identitiesJob =
                launch {
                    identityRepo.getActiveIdentities().collect {
                        identitiesList.add(it)
                    }
                }

            // Trigger fallback by advancing time past seed generation timeout
            testScheduler.advanceTimeBy(30.seconds)
            advanceUntilIdle()

            // At this point, seedFlow should have emitted FALLBACK_SEED.
            // Since getActiveSubscriptions() now returns our mock, identitiesList should have an entry.
            assertTrue(
                "Expected fallback identity emission. List: $identitiesList",
                identitiesList.isNotEmpty() && identitiesList.any { it.isNotEmpty() },
            )

            val fallbackIdentity = identitiesList.last().first()
            assertTrue("Expected fallback identity format", fallbackIdentity.startsWith("sim_"))

            // Now provide real seed
            storedSeed.emit("real_seed")
            advanceUntilIdle()

            // It should now have emitted identities based on real_seed.
            assertTrue(
                "Expected identities based on real_seed in $identitiesList. Last emission: ${identitiesList.lastOrNull()}",
                identitiesList.isNotEmpty() && identitiesList.last().isNotEmpty() &&
                    identitiesList.last().first() != fallbackIdentity,
            )

            identitiesJob.cancel()
        }
}
