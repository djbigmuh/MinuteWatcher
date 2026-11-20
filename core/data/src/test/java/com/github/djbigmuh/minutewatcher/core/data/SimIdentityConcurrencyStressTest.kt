package com.github.djbigmuh.minutewatcher.core.data

import android.content.Context
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.github.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import com.github.djbigmuh.minutewatcher.core.common.util.PermissionManager
import com.github.djbigmuh.minutewatcher.core.common.util.TimeProvider
import com.github.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import com.github.djbigmuh.minutewatcher.core.domain.PermissionStateNotifier
import com.github.djbigmuh.minutewatcher.core.model.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
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
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SimIdentityConcurrencyStressTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var subscriptionManager: SubscriptionManager
    private lateinit var permissionStateNotifier: PermissionStateNotifier
    private lateinit var appSettingsRepository: AppSettingsRepository
    private lateinit var permissionManager: PermissionManager
    private lateinit var timeProvider: TimeProvider
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
        permissionStateNotifier = mock()
        permissionManager = mock()
        timeProvider =
            mock {
                on { elapsedRealtime() } doReturn 1000L
            }
        migrationService =
            mock {
                on { migrateLegacyData(any()) } doReturn IdentityMigrationService.MigrationOutcome(migrated = 1)
            }
        appSettingsRepository =
            mock {
                on { observeAppSettings() } doReturn MutableStateFlow(AppSettings())
            }
        whenever(permissionStateNotifier.changes).doReturn(MutableSharedFlow())
        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)

        val subInfo =
            mock<SubscriptionInfo> {
                on { simSlotIndex } doReturn 0
                on { subscriptionId } doReturn 101
                on { iccId } doReturn "iccid_slot_0"
            }
        whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(subInfo))

        discoveryDeps =
            SimDiscoveryDependencies(
                context = context,
                subscriptionManager = subscriptionManager,
                permissionManager = permissionManager,
                permissionStateNotifier = permissionStateNotifier,
            )
    }

    private fun TestScope.createTestDataStore(): androidx.datastore.core.DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tmpFolder.newFile("stress_ds_${UUID.randomUUID()}.preferences_pb") },
        )

    @Test
    fun `concurrent getSimIdentity calls for same slot produce identical stable results without race condition`() =
        runTest {
            val ds = createTestDataStore()
            val seedRepo = SimIdentitySeedRepositoryImpl(ds)

            val repository =
                SimIdentityRepositoryTestHelper.createRepository(
                    discoveryDeps = discoveryDeps,
                    dataStore = ds,
                    seedRepository = seedRepo,
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    timeProvider = timeProvider,
                    scope = backgroundScope,
                )

            // Launch 20 concurrent coroutines requesting identity for slot 0
            val deferreds =
                (1..20).map {
                    async {
                        repository.getSimIdentity(0)
                    }
                }

            val results = deferreds.awaitAll()

            // Verify that all results are identical
            val firstId = results.first()
            assertTrue("Results should not be empty", results.isNotEmpty())
            for (res in results) {
                assertEquals(
                    "All concurrent resolutions must yield the exact same stableId",
                    firstId.stableId,
                    res.stableId,
                )
                assertEquals(
                    "All concurrent resolutions must yield the exact same slotIndex",
                    firstId.slotIndex,
                    res.slotIndex,
                )
            }
        }
}
