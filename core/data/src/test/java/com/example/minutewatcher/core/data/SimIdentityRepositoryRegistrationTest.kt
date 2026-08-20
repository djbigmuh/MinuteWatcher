package com.example.minutewatcher.core.data

import android.content.Context
import android.telephony.SubscriptionManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.model.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SimIdentityRepositoryRegistrationTest {
    private lateinit var context: Context
    private lateinit var subscriptionManager: SubscriptionManager
    private lateinit var permissionManager: PermissionManager
    private lateinit var permissionStateNotifier: PermissionStateNotifier
    private lateinit var discoveryDeps: SimDiscoveryDependencies

    private val dataStore: DataStore<Preferences> = mock()
    private val seedRepository: SimIdentitySeedRepository = mock()
    private val appSettingsRepository: AppSettingsRepository = mock()
    private val migrationService: IdentityMigrationService = mock()
    private val mockSimProvider: MockSimProvider = mock()
    private val timeProvider: TimeProvider = mock()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher + Job())

    @Before
    fun setup() {
        context = mock()
        subscriptionManager = mock()
        permissionManager = mock()
        permissionStateNotifier = mock()

        whenever(context.mainExecutor).thenReturn(Executor { it.run() })

        discoveryDeps =
            SimDiscoveryDependencies(
                context = context,
                subscriptionManager = subscriptionManager,
                permissionManager = permissionManager,
                permissionStateNotifier = permissionStateNotifier,
            )

        whenever(appSettingsRepository.observeAppSettings()).thenReturn(MutableStateFlow(AppSettings()))
        whenever(seedRepository.seedFlow).thenReturn(flowOf("test_seed"))
        whenever(permissionStateNotifier.changes).thenReturn(flowOf(Unit))
        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
        whenever(permissionManager.getActiveSubscriptions()).thenReturn(emptyList())
    }

    @Test
    @Config(sdk = [30])
    @Suppress("DEPRECATION")
    fun `test addOnSubscriptionsChangedListener uses executor on API 30`() =
        runTest(testDispatcher) {
            val repository = createRepository()

            val job =
                launch {
                    repository.observeActiveSims().collect {}
                }

            verify(subscriptionManager).addOnSubscriptionsChangedListener(any<Executor>(), any())
            verify(subscriptionManager, never())
                .addOnSubscriptionsChangedListener(any<SubscriptionManager.OnSubscriptionsChangedListener>())

            job.cancel()
        }

    @Test
    @Config(sdk = [29])
    @Suppress("DEPRECATION")
    fun `test addOnSubscriptionsChangedListener uses legacy on API 29`() =
        runTest(testDispatcher) {
            val repository = createRepository()

            val job =
                launch {
                    repository.observeActiveSims().collect {}
                }

            verify(subscriptionManager)
                .addOnSubscriptionsChangedListener(any<SubscriptionManager.OnSubscriptionsChangedListener>())

            job.cancel()
        }

    private fun createRepository() =
        SimIdentityRepositoryImpl(
            discoveryDeps = discoveryDeps,
            dataStore = dataStore,
            seedRepository = seedRepository,
            appSettingsRepository = appSettingsRepository,
            migrationService = migrationService,
            mockSimProvider = mockSimProvider,
            timeProvider = timeProvider,
            scope = testScope,
        )
}
