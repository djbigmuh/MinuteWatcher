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
import com.example.minutewatcher.core.model.IdentitySource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
class SimIdentityRepositoryHangTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var subscriptionManager: SubscriptionManager
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var seedRepository: SimIdentitySeedRepository
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

        seedRepository = mock {
            on { getSeed() } doReturn flowOf("test_seed")
            on { getOrCreateSeed(any()) } doReturn "test_seed"
        }
        whenever(permissionStateNotifier.changes).thenReturn(flowOf(Unit))

        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { tmpFolder.newFile("test_sim_settings.preferences_pb") }
        )
    }

    @Test
    fun `getSimIdentity returns fallback when activeSimsCache hangs`() = runTest {
        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
        whenever(permissionManager.getActiveSubscriptions()).thenReturn(emptyList())

        val identityRepo = SimIdentityRepositoryImpl(
            context,
            subscriptionManager,
            permissionManager,
            permissionStateNotifier,
            dataStore,
            seedRepository,
            appSettingsRepository,
            migrationService,
            mockSimProvider,
            backgroundScope
        )

        // Mock seedRepository.getSeed() to return a flow that never emits,
        // which will cause activeSimsCache to wait indefinitely.
        whenever(seedRepository.getSeed()).thenReturn(MutableSharedFlow())

        // Act
        val identity = identityRepo.getSimIdentity(0)

        // Assert: It should return fallback after 10s timeout instead of hanging forever
        assertEquals("sim_0", identity.stableId)
        assertEquals(IdentitySource.SLOT_FALLBACK, identity.source)
    }
}
