package com.example.minutewatcher.core.data

import android.content.Context
import android.telephony.SubscriptionManager
import com.example.minutewatcher.core.domain.SettingsRepository
import com.example.minutewatcher.core.domain.SimRepository
import com.example.minutewatcher.core.model.util.PermissionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class TrafficTrackerTest {

    private lateinit var context: Context
    private lateinit var simRepository: SimRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var subscriptionManager: SubscriptionManager
    private lateinit var permissionManager: PermissionManager
    
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val appScope = CoroutineScope(SupervisorJob() + testDispatcher)

    @Before
    fun setup() {
        context = mock()
        simRepository = mock()
        settingsRepository = mock()
        subscriptionManager = mock()
        permissionManager = mock()

        runBlocking {
            whenever(settingsRepository.getLastDataSubId()).thenReturn(flowOf(-1))
            whenever(simRepository.getIdentity(any())).thenReturn("test_id")
            whenever(simRepository.getSimOperator(any())).thenReturn(flowOf("Test Operator"))
        }
    }

    @Test
    fun testInitialization() = testScope.runTest {
        TrafficTracker(
            context,
            simRepository,
            settingsRepository,
            permissionManager,
            appScope
        )
        // Basic sanity check
    }
}
