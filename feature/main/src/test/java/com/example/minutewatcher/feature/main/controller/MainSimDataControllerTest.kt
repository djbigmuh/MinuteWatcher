package com.example.minutewatcher.feature.main.controller

import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimLimitsRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.UsageUseCase
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.SimCardUiModel
import com.example.minutewatcher.core.resources.R
import com.example.minutewatcher.feature.main.SimCardListState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MainSimDataControllerTest {
    @get:Rule
    val mainDispatcherRule =
        MainDispatcherRule(
            UnconfinedTestDispatcher(),
        )

    private val usageStateRepository: UsageStateRepository = mock()
    private val trackingRecoveryController: TrackingRecoveryController = mock()
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simLimitsRepository: SimLimitsRepository = mock()
    private val usageUseCase: UsageUseCase = mock()
    private val dataChangeNotifier: DataChangeNotifier = mock()

    private lateinit var controller: MainSimDataController

    @Before
    fun setup() {
        initTestLogger()
        whenever(usageStateRepository.observeSimCardUiModels()).thenReturn(flowOf(emptyList()))
        whenever(usageStateRepository.isDataLoaded()).thenReturn(true)
    }

    @Test
    fun `adjustRemainingMinutes failure sets lastOperationError`() =
        runTest {
            controller = createController()
            val slot = 0
            whenever(usageUseCase.adjustMinutes(eq(slot), any())).thenAnswer {
                throw IOException("Storage error")
            }

            controller.adjustRemainingMinutes(slot, 100)
            testScheduler.advanceTimeBy(1000.milliseconds)
            testScheduler.runCurrent()
            testScheduler.advanceUntilIdle()

            val error = controller.lastOperationError.value
            assertEquals(R.string.error_operation_failed_retry, error?.messageRes)
            assertEquals(slot, error?.slot)
        }

    private fun TestScope.createController(): MainSimDataController =
        MainSimDataController(
            usageStateRepository,
            trackingRecoveryController,
            simIdentityRepository,
            simLimitsRepository,
            usageUseCase,
            dataChangeNotifier,
            mainDispatcherRule.testDispatcher,
            backgroundScope,
        )

    @Test
    fun `highlightSlot sets and clears slot after delay`() =
        runTest {
            controller = createController()
            controller.highlightSlot(1)
            testScheduler.runCurrent()
            assertEquals(1, controller.highlightedSlot.value)

            advanceTimeBy(Constants.UI.HIGHLIGHT_DELAY_MS.milliseconds)
            testScheduler.runCurrent()
            assertNull(controller.highlightedSlot.value)
        }

    @Test
    fun `highlightSlot concurrent calls do not get stuck`() =
        runTest {
            controller = createController()
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
            advanceTimeBy(Constants.UI.HIGHLIGHT_DELAY_MS.milliseconds)
            testScheduler.runCurrent()

            // Should be cleared
            assertNull("Highlight should be cleared after delay", controller.highlightedSlot.value)
        }

    @Test
    fun `highlightSlot repeated call for same slot does not restart timer`() =
        runTest {
            controller = createController()
            controller.highlightSlot(1)
            testScheduler.runCurrent()
            advanceTimeBy((Constants.UI.HIGHLIGHT_DELAY_MS / 2).milliseconds)
            testScheduler.runCurrent()
            assertEquals(1, controller.highlightedSlot.value)

            // Call again for same slot
            controller.highlightSlot(1)
            testScheduler.runCurrent()

            // Advance remaining half of initial delay
            advanceTimeBy(((Constants.UI.HIGHLIGHT_DELAY_MS / 2) + 10).milliseconds)
            testScheduler.runCurrent()

            // Should be cleared because timer was NOT restarted
            assertNull("Highlight should be cleared based on first timer", controller.highlightedSlot.value)
        }

    @Test
    fun `uiState stays in Loading when data not loaded and list is empty`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val modelsFlow = MutableStateFlow(emptyList<SimCardUiModel>())
            whenever(usageStateRepository.observeSimCardUiModels()).thenReturn(modelsFlow)
            whenever(usageStateRepository.isDataLoaded()).thenReturn(false)

            controller = createController()
            val uiState = controller.observeUiState(backgroundScope)

            val job = launch { uiState.collect {} }
            testScheduler.runCurrent()
            assertEquals(SimCardListState.Loading, uiState.value)
            job.cancel()
        }

    @Test
    fun `uiState transitions to Empty when data loaded and list is empty`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val modelsFlow = MutableStateFlow(emptyList<SimCardUiModel>())
            whenever(usageStateRepository.observeSimCardUiModels()).thenReturn(modelsFlow)
            whenever(usageStateRepository.isDataLoaded()).thenReturn(true)

            controller = createController()
            val uiState = controller.observeUiState(backgroundScope)

            val job = launch { uiState.collect {} }
            testScheduler.runCurrent()
            assertEquals(SimCardListState.Empty, uiState.value)
            job.cancel()
        }

    @Test
    fun `observeUiState is lazy and does not start collection on creation`() =
        runTest {
            val modelsFlow = MutableSharedFlow<List<SimCardUiModel>>()
            whenever(usageStateRepository.observeSimCardUiModels()).thenReturn(modelsFlow)

            controller = createController()
            testScheduler.runCurrent()

            assertEquals("Flow should not be collected yet", 0, modelsFlow.subscriptionCount.value)

            val job =
                launch {
                    controller.observeUiState(this).collect {}
                }
            testScheduler.runCurrent()
            assertEquals("Flow should be collected after subscription", 1, modelsFlow.subscriptionCount.value)

            job.cancel()
            testScheduler.runCurrent()
            // advanceTimeBy(5001) to trigger WhileSubscribed timeout if needed,
            // but in tests we usually check immediately if it's properly bound.
        }

    @Test
    fun `executeSimOperation succeeds even when widget update fails`() =
        runTest {
            controller = createController()
            val slot = 0
            val identity = "sim_0"
            whenever(simIdentityRepository.getIdentity(slot)).thenReturn(identity)

            // Operation succeeds, but widget update fails
            whenever(dataChangeNotifier.notifyDataChanged(any(), any())).thenReturn(false)

            controller.updateLimits(slot, 100, 1024)
            testScheduler.runCurrent()

            val error = controller.lastOperationError.value
            assertNull("Operation should be successful even if widget update fails", error)
        }
}
