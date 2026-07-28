package com.example.minutewatcher.core.data

import android.content.Context
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.util.PermissionManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class SimVisibilityTest {
    private val context: Context = mock()
    private val subscriptionManager: SubscriptionManager = mock()
    private val permissionManager: PermissionManager = mock()
    private val permissionStateNotifier: PermissionStateNotifier = mock()
    private val dataStore: DataStore<Preferences> = mock()
    private val seedRepository: SimIdentitySeedRepository = mock()

    private val permissionChanges = MutableSharedFlow<Int>(replay = 1)

    @Before
    fun setup() {
        whenever(permissionStateNotifier.changes).thenReturn(permissionChanges.map { })
        whenever(seedRepository.getSeed()).thenReturn(flowOf("test_seed"))
    }

    @Test
    fun `observeActiveSims returns empty list when permission is missing`() =
        runTest {
            permissionChanges.emit(0)
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

            val simsList = mutableListOf<List<ActiveSimInfo>>()
            val job =
                launch(UnconfinedTestDispatcher(testScheduler)) {
                    repository.observeActiveSims().collect { simsList.add(it) }
                }

            permissionChanges.emit(0)
            runCurrent()

            // Initial emission (empty)
            assertTrue(simsList.isNotEmpty())
            val initialSize = simsList.size

            // Now grant permission AND PROVIDE A SIM to change the result
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            val mockInfo = mock<SubscriptionInfo>()
            whenever(mockInfo.simSlotIndex).thenReturn(0)
            whenever(mockInfo.subscriptionId).thenReturn(1)
            whenever(mockInfo.iccId).thenReturn("test_iccid")
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(mockInfo))

            permissionChanges.emit(1)
            advanceUntilIdle()

            // Should have emitted again
            assertTrue("Should have more than $initialSize emissions, but had ${simsList.size}", simsList.size > initialSize)

            job.cancel()
        }
}
