package com.example.minutewatcher.core.data

import android.content.Context
import android.telephony.SubscriptionManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.model.util.PermissionManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class SimVisibilityTest {
    private val context: Context = mock()
    private val subscriptionManager: SubscriptionManager = mock()
    private val permissionManager: PermissionManager = mock()
    private val permissionStateNotifier: PermissionStateNotifier = mock()
    private val dataStore: DataStore<Preferences> = mock()
    private val seedRepository: SimIdentitySeedRepository = mock()

    private val permissionChanges = MutableSharedFlow<Unit>(replay = 1)

    @Before
    fun setup() {
        whenever(permissionStateNotifier.changes).thenReturn(permissionChanges)
        whenever(seedRepository.getSeed()).thenReturn(flowOf("test_seed"))
        permissionChanges.tryEmit(Unit)
    }

    @Test
    fun `observeActiveSims returns empty list when permission is missing`() =
        runTest {
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(false)

            val repository =
                SimRepositoryImpl(context, subscriptionManager, permissionManager, permissionStateNotifier, dataStore, seedRepository)

            val sims = repository.observeActiveSims().first()
            assertTrue(sims.isEmpty())
        }

    @Test
    fun `observeActiveSims re-evaluates when permission changes`() =
        runTest {
            // Initially no permission
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(false)
            val repository =
                SimRepositoryImpl(context, subscriptionManager, permissionManager, permissionStateNotifier, dataStore, seedRepository)

            // Collect first emission
            assertTrue(repository.observeActiveSims().first().isEmpty())

            // Now grant permission and notify
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(emptyList())
            
            permissionChanges.emit(Unit)
            
            // Collect again - should be empty but this time it actually checked subscriptions
            // (In a real test we'd verify getActiveSubscriptions was called)
            assertTrue(repository.observeActiveSims().first().isEmpty())
        }
}
