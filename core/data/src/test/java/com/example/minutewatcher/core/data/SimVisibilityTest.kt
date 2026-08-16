package com.example.minutewatcher.core.data

import android.content.Context
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.model.ActiveSimInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
class SimVisibilityTest {
    private val context: Context = mock()
    private val subscriptionManager: SubscriptionManager = mock()
    private val permissionManager: PermissionManager = mock()
    private val permissionStateNotifier: PermissionStateNotifier = mock()
    private val appSettingsRepository: com.example.minutewatcher.core.domain.AppSettingsRepository =
        mock {
            on { observeAppSettings() } doReturn
                kotlinx.coroutines.flow.MutableStateFlow(
                    com.example.minutewatcher.core.model
                        .AppSettings(),
                )
        }
    private val dataStore: DataStore<Preferences> =
        mock {
            on { data } doReturn
                flowOf(
                    androidx.datastore.preferences.core
                        .emptyPreferences(),
                )
        }
    private val seedRepository: SimIdentitySeedRepository =
        mock {
            on { seedFlow } doReturn flowOf("test_seed")
            on { getOrCreateSeed(any()) } doReturn "test_seed"
        }
    private val migrationService: IdentityMigrationService = mock()
    private val mockSimProvider: MockSimProvider =
        mock {
            on { getMockSims(any()) }.thenReturn(null)
        }
    private lateinit var discoveryDeps: SimDiscoveryDependencies

    private val permissionChanges = MutableSharedFlow<Int>(replay = 1)

    @Before
    fun setup() {
        whenever(permissionStateNotifier.changes).thenReturn(permissionChanges.map { })
        permissionChanges.tryEmit(0)

        discoveryDeps =
            SimDiscoveryDependencies(
                context = context,
                subscriptionManager = subscriptionManager,
                permissionManager = permissionManager,
                permissionStateNotifier = permissionStateNotifier,
            )
    }

    @Test
    fun `observeActiveSims returns empty list when permission is missing`() =
        runTest {
            permissionChanges.emit(0)
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(false)

            val repository =
                SimIdentityRepositoryImpl(
                    discoveryDeps,
                    dataStore,
                    seedRepository,
                    appSettingsRepository,
                    migrationService,
                    mockSimProvider,
                    mock(),
                    backgroundScope,
                )

            val sims = repository.observeActiveSims().first()
            assertTrue(sims.isEmpty())
        }

    @Test
    fun `observeActiveSims re-evaluates when permission changes`() =
        runTest {
            // Initially no permission
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(false)
            val repository =
                SimIdentityRepositoryImpl(
                    discoveryDeps = discoveryDeps,
                    dataStore = dataStore,
                    seedRepository = seedRepository,
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    timeProvider = mock(),
                    scope = backgroundScope,
                )

            val simsList = mutableListOf<List<ActiveSimInfo>>()
            backgroundScope.launch {
                repository.observeActiveSims().collect { simsList.add(it) }
            }

            // Initial value from StateFlow (initialValue = emptyList())
            runCurrent()
            assertTrue(simsList.isNotEmpty())

            // Trigger first re-evaluation
            permissionChanges.emit(0)
            runCurrent()

            // Now grant permission AND PROVIDE A SIM to change the result
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            val mockInfo = mock<SubscriptionInfo>()
            whenever(mockInfo.simSlotIndex).thenReturn(0)
            whenever(mockInfo.subscriptionId).thenReturn(1)
            whenever(mockInfo.iccId).thenReturn("test_iccid")
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(mockInfo))

            permissionChanges.emit(1)
            runCurrent()

            // Should have emitted again with the new SIM
            assertTrue("Last emission should contain the SIM", simsList.last().isNotEmpty())
        }
}
