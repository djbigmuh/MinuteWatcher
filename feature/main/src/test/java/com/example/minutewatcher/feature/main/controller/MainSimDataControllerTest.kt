package com.example.minutewatcher.feature.main.controller

import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimLimitsRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.UsageUseCase
import com.example.minutewatcher.core.model.Constants
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MainSimDataControllerTest {
    private val usageStateRepository: UsageStateRepository = mock()
    private val trackingRecoveryController: TrackingRecoveryController = mock()
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simLimitsRepository: SimLimitsRepository = mock()
    private val usageUseCase: UsageUseCase = mock()
    private val dataChangeNotifier: DataChangeNotifier = mock()
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var controller: MainSimDataController

    @Before
    fun setup() {
        initTestLogger()
        whenever(usageStateRepository.observeSimCardUiModels()).thenReturn(flowOf(emptyList()))
        controller =
            MainSimDataController(
                usageStateRepository,
                trackingRecoveryController,
                simIdentityRepository,
                simLimitsRepository,
                usageUseCase,
                dataChangeNotifier,
                testDispatcher,
                testScope,
            )
    }

    @Test
    fun `highlightSlot sets and clears slot after delay`() =
        testScope.runTest {
            controller.highlightSlot(1)
            testScheduler.runCurrent()
            assertEquals(1, controller.highlightedSlot.value)

            advanceTimeBy(Constants.UI.HIGHLIGHT_DELAY_MS)
            testScheduler.runCurrent()
            assertNull(controller.highlightedSlot.value)
        }

    @Test
    fun `highlightSlot concurrent calls do not get stuck`() =
        testScope.runTest {
            // Rapidly call highlightSlot for different slots
            repeat(10) { slot ->
                launch {
                    controller.highlightSlot(slot)
                }
            }
            testScheduler.runCurrent()

            // The last one should be active
            assertEquals(9, controller.highlightedSlot.value)

            // Wait for delay
            advanceTimeBy(Constants.UI.HIGHLIGHT_DELAY_MS)
            testScheduler.runCurrent()

            // Should be cleared
            assertNull("Highlight should be cleared after delay", controller.highlightedSlot.value)
        }

    @Test
    fun `highlightSlot repeated call for same slot does not restart timer`() =
        testScope.runTest {
            controller.highlightSlot(1)
            testScheduler.runCurrent()
            advanceTimeBy(Constants.UI.HIGHLIGHT_DELAY_MS / 2)
            testScheduler.runCurrent()
            assertEquals(1, controller.highlightedSlot.value)

            // Call again for same slot
            controller.highlightSlot(1)
            testScheduler.runCurrent()

            // Advance remaining half of initial delay
            advanceTimeBy(Constants.UI.HIGHLIGHT_DELAY_MS / 2 + 10)
            testScheduler.runCurrent()

            // Should be cleared because timer was NOT restarted
            assertNull("Highlight should be cleared based on first timer", controller.highlightedSlot.value)
        }
}
