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
    }

    @Test
    fun `seedFlow recovers from fallback when seed eventually becomes available`() =
        runTest {
            val activeSub = mock<android.telephony.SubscriptionInfo>()
            whenever(activeSub.simSlotIndex).thenReturn(0)
            whenever(activeSub.subscriptionId).thenReturn(1)
            whenever(activeSub.displayName).thenReturn("SIM 1")
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(activeSub))

            val storedSeed = MutableStateFlow("")
            val seedRepo =
                object : SimIdentitySeedRepository {
                    override fun getSeed(): Flow<String> = storedSeed

                    override suspend fun setSeed(seed: String) {
                        storedSeed.value = seed
                    }

                    override suspend fun getOrCreateSeed(generator: () -> String): String {
                        delay(60_000) // гарантированно больше SEED_GENERATION_TIMEOUT_MS и второй попытки
                        return generator()
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

            val identitiesList = mutableListOf<List<String>>()
            val identitiesJob =
                launch {
                    identityRepo.getActiveIdentities().collect {
                        identitiesList.add(it)
                    }
                }

            advanceUntilIdle() // This should trigger the timeout and emission of FALLBACK_SEED

            // После первого advanceUntilIdle() assert'ить, что есть эмиссия с "sim_0"
            assertTrue(identitiesList.any { it.contains("sim_0") })

            // Затем storedSeed.value = "real_seed";
            storedSeed.value = "real_seed"
            advanceUntilIdle()

            // после второго advanceUntilIdle() assert'ить, что появилась эмиссия с "sub_1"
            assertTrue(identitiesList.any { it.contains("sub_1") })

            identitiesJob.cancel()
        }
}
