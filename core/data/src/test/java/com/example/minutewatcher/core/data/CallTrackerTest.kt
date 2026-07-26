package com.example.minutewatcher.core.data

import android.content.Context
import com.example.minutewatcher.core.domain.SettingsRepository
import com.example.minutewatcher.core.domain.SimRepository
import com.example.minutewatcher.core.model.util.PermissionManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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

    private val callStartTimeFlow = MutableStateFlow(0L)
    private val callTrackingEnabledFlow = MutableStateFlow(true)
    private val lastCallSubIdFlow = MutableStateFlow(-1)
    private val bootTimeFlow = MutableStateFlow(0L)

    @Before
    fun setup() =
        runTest {
            whenever(settingsRepository.getCallTrackingEnabled()).thenReturn(callTrackingEnabledFlow)
            whenever(settingsRepository.getCallStartTime()).thenReturn(callStartTimeFlow)
            whenever(settingsRepository.getBootTime()).thenReturn(bootTimeFlow)
            whenever(settingsRepository.getLastCallSubId()).thenReturn(lastCallSubIdFlow)

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
    fun `test call start saves state`() =
        runTest {
            callTracker.onCallStarted(subId = 1)

            verify(settingsRepository).setCallStartTime(any())
            verify(settingsRepository).setLastCallSubId(1)
        }

    @Test
    fun `test call end with different subId is ignored`() =
        runTest {
            // Prepare: start call on subId 1
            callStartTimeFlow.value = 1000L
            lastCallSubIdFlow.value = 1

            // Act: end call on subId 2
            callTracker.onCallEnded(subId = 2)

            // Assert: state should not be cleared for subId 1
            verify(settingsRepository, never()).setCallStartTime(0)
        }

    @Test
    fun `test call end with same subId clears state`() =
        runTest {
            callStartTimeFlow.value = 1000L
            lastCallSubIdFlow.value = 1

            callTracker.onCallEnded(subId = 1)

            verify(settingsRepository).setCallStartTime(0)
            verify(settingsRepository).setLastCallSubId(-1)
        }
}
