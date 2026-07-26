package com.example.minutewatcher.core.data

import android.content.Context
import com.example.minutewatcher.core.domain.SettingsRepository
import com.example.minutewatcher.core.domain.SimRepository
import com.example.minutewatcher.core.model.util.PermissionManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class CallTrackerTest {
    private val context: Context = mock()
    private val simRepository: SimRepository = mock()
    private val settingsRepository: SettingsRepository = mock()
    private val updateCoordinator: UpdateCoordinator = mock()
    private val permissionManager: PermissionManager = mock()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var callTracker: CallTracker

    @Before
    fun setup() =
        runTest {
            whenever(settingsRepository.getCallTrackingEnabled()).thenReturn(flowOf(true))
            whenever(settingsRepository.getCallStartTime(any())).thenReturn(flowOf(0L))
            whenever(settingsRepository.getBootTime(any())).thenReturn(flowOf(0L))
            whenever(settingsRepository.getCallIdentity(any())).thenReturn(flowOf(null))

            whenever(simRepository.getIdentity(0)).thenReturn("id_0")
            whenever(simRepository.getIdentity(1)).thenReturn("id_1")
            whenever(simRepository.getSimOperator(any())).thenReturn(flowOf("MTS"))

            callTracker =
                CallTracker(
                    context,
                    simRepository,
                    settingsRepository,
                    updateCoordinator,
                    permissionManager,
                    testScope,
                )
        }

    @Test
    fun `test call start saves state for specific subId`() =
        runTest {
            callTracker.onCallStarted(subId = 1)

            verify(settingsRepository).setCallStartTime(eq(1), any())
            verify(settingsRepository).setCallIdentity(eq(1), anyOrNull())
        }

    @Test
    fun `test call end with same subId clears state`() =
        runTest {
            whenever(settingsRepository.getCallStartTime(1)).thenReturn(flowOf(1000L))
            whenever(settingsRepository.getBootTime(1)).thenReturn(flowOf(100L))

            callTracker.onCallEnded(subId = 1)

            verify(settingsRepository).setCallStartTime(1, 0)
            verify(settingsRepository).setBootTime(1, 0)
        }
        
    @Test
    fun `test dual SIM concurrent call tracking`() = runTest {
        // Start call on subId 1
        callTracker.onCallStarted(subId = 1)
        verify(settingsRepository).setCallStartTime(eq(1), any())
        
        // Start call on subId 2
        callTracker.onCallStarted(subId = 2)
        verify(settingsRepository).setCallStartTime(eq(2), any())
    }
}
