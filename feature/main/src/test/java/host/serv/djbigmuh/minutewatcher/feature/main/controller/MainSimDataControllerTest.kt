package host.serv.djbigmuh.minutewatcher.feature.main.controller

import host.serv.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import host.serv.djbigmuh.minutewatcher.core.common.util.initTestLogger
import host.serv.djbigmuh.minutewatcher.core.domain.DataChangeNotifier
import host.serv.djbigmuh.minutewatcher.core.domain.SimIdentityRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimLimitsRepository
import host.serv.djbigmuh.minutewatcher.core.domain.TrackingStatusRepository
import host.serv.djbigmuh.minutewatcher.core.domain.UsageStateRepository
import host.serv.djbigmuh.minutewatcher.core.domain.UsageUseCase
import host.serv.djbigmuh.minutewatcher.core.model.Constants
import host.serv.djbigmuh.minutewatcher.core.model.SimCardUiModel
import host.serv.djbigmuh.minutewatcher.core.model.TrackingKey
import host.serv.djbigmuh.minutewatcher.core.model.TrackingStatus
import host.serv.djbigmuh.minutewatcher.core.resources.R
import host.serv.djbigmuh.minutewatcher.feature.main.SimCardListState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class MainSimDataControllerTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val usageStateRepository: UsageStateRepository = mock()
    private val trackingRecoveryController: TrackingRecoveryController = mock()
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simLimitsRepository: SimLimitsRepository = mock()
    private val usageUseCase: UsageUseCase = mock()
    private val dataChangeNotifier: DataChangeNotifier = mock()
    private val trackingStatusRepository: TrackingStatusRepository = mock()

    private lateinit var controller: MainSimDataController

    @Before
    fun setup() {
        initTestLogger()
        whenever(usageStateRepository.observeSimCardUiModels()).thenReturn(flowOf(emptyList()))
        whenever(usageStateRepository.isDataLoaded()).thenReturn(true)
        whenever(trackingStatusRepository.observeTrackingStatus()).thenReturn(flowOf(emptyMap()))
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

    @Test
    fun `toggleAutoReset failure on day read timeout sets lastOperationError`() =
        runTest {
            controller = createController()
            val slot = 0
            val identity = "sim_0"
            whenever(simIdentityRepository.getIdentity(slot)).thenReturn(identity)
            // Mock timeout: flow that never emits
            whenever(simLimitsRepository.getAutoResetDay(identity)).thenReturn(flow { awaitCancellation() })

            controller.toggleAutoReset(slot, true)

            // Advance time past timeouts: 5s (first) + 0.5s (delay) + 5s (second) = 10.5s
            testScheduler.advanceTimeBy(15000.milliseconds)
            testScheduler.runCurrent()

            val error = controller.lastOperationError.value
            assertEquals(R.string.error_operation_failed_retry, error?.messageRes)
            assertEquals(slot, error?.slot)
            verify(simLimitsRepository, never()).setAutoReset(any(), any(), any())
        }

    @Test
    fun `updateAutoResetDay failure on status read timeout sets lastOperationError`() =
        runTest {
            controller = createController()
            val slot = 0
            val identity = "sim_0"
            whenever(simIdentityRepository.getIdentity(slot)).thenReturn(identity)
            // Mock timeout: flow that never emits
            whenever(simLimitsRepository.getAutoReset(identity)).thenReturn(flow { awaitCancellation() })

            controller.updateAutoResetDay(slot, 15)

            // Advance time past timeouts: 5s (first) + 0.5s (delay) + 5s (second) = 10.5s
            testScheduler.advanceTimeBy(15000.milliseconds)
            testScheduler.runCurrent()

            val error = controller.lastOperationError.value
            assertEquals(R.string.error_operation_failed_retry, error?.messageRes)
            assertEquals(slot, error?.slot)
            verify(simLimitsRepository, never()).setAutoReset(any(), any(), any())
        }

    private fun TestScope.createController(): MainSimDataController =
        MainSimDataController(
            usageStateRepository,
            trackingRecoveryController,
            simIdentityRepository,
            simLimitsRepository,
            usageUseCase,
            dataChangeNotifier,
            trackingStatusRepository,
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

    @Test
    fun `regression - operation with hanging block completes with error after total timeout`() =
        runTest {
            val hangingLimitsRepository =
                object : SimLimitsRepository by mock() {
                    override suspend fun updateSimLimits(
                        identity: String,
                        minutesLimit: Int,
                        trafficLimitMb: Int,
                        rolloverEnabled: Boolean,
                    ) {
                        awaitCancellation()
                    }
                }

            controller =
                MainSimDataController(
                    usageStateRepository,
                    trackingRecoveryController,
                    simIdentityRepository,
                    hangingLimitsRepository,
                    usageUseCase,
                    dataChangeNotifier,
                    trackingStatusRepository,
                    mainDispatcherRule.testDispatcher,
                    backgroundScope,
                )

            val slot = 0
            val identity = "sim_0"
            whenever(simIdentityRepository.getIdentity(slot)).thenReturn(identity)

            controller.updateLimits(slot, 100, 1024)
            testScheduler.runCurrent()

            // Advance past LONG_TIMEOUT_MS (10s) + RETRY_BASE_DELAY_MS (0.5s) + SHORT_TIMEOUT_MS (5s)
            testScheduler.advanceTimeBy(16000.milliseconds)
            testScheduler.runCurrent()

            val error = controller.lastOperationError.value
            assertEquals(R.string.error_operation_failed_retry, error?.messageRes)
            assertEquals(slot, error?.slot)
        }

    @Test
    fun `retryAppSearch does not crash on exception`() =
        runTest {
            var invokedKey: TrackingKey? = null
            val fakeStatusRepository =
                object : TrackingStatusRepository {
                    override suspend fun recoverTracking(key: TrackingKey) {
                        invokedKey = key
                        throw IllegalStateException("Recovery failed")
                    }

                    override fun observeTrackingStatus() = flowOf(emptyMap<TrackingKey, TrackingStatus>())
                }
            val recoveryController = TrackingRecoveryController(fakeStatusRepository)
            controller =
                MainSimDataController(
                    usageStateRepository,
                    recoveryController,
                    simIdentityRepository,
                    simLimitsRepository,
                    usageUseCase,
                    dataChangeNotifier,
                    trackingStatusRepository,
                    mainDispatcherRule.testDispatcher,
                    backgroundScope,
                )
            controller.retryAppSearch()
            testScheduler.runCurrent()
            testScheduler.advanceUntilIdle()
            assertEquals(TrackingKey.APPSEARCH, invokedKey)
        }

    @Test
    fun `retryCalls does not crash on exception`() =
        runTest {
            var invokedKey: TrackingKey? = null
            val fakeStatusRepository =
                object : TrackingStatusRepository {
                    override suspend fun recoverTracking(key: TrackingKey) {
                        invokedKey = key
                        throw IllegalStateException("Recovery failed")
                    }

                    override fun observeTrackingStatus() = flowOf(emptyMap<TrackingKey, TrackingStatus>())
                }
            val recoveryController = TrackingRecoveryController(fakeStatusRepository)
            controller =
                MainSimDataController(
                    usageStateRepository,
                    recoveryController,
                    simIdentityRepository,
                    simLimitsRepository,
                    usageUseCase,
                    dataChangeNotifier,
                    trackingStatusRepository,
                    mainDispatcherRule.testDispatcher,
                    backgroundScope,
                )
            controller.retryCalls()
            testScheduler.runCurrent()
            testScheduler.advanceUntilIdle()
            assertEquals(TrackingKey.CALLS, invokedKey)
        }

    @Test
    fun `retryShortcuts does not crash on exception`() =
        runTest {
            var invokedKey: TrackingKey? = null
            val fakeStatusRepository =
                object : TrackingStatusRepository {
                    override suspend fun recoverTracking(key: TrackingKey) {
                        invokedKey = key
                        throw IllegalStateException("Recovery failed")
                    }

                    override fun observeTrackingStatus() = flowOf(emptyMap<TrackingKey, TrackingStatus>())
                }
            val recoveryController = TrackingRecoveryController(fakeStatusRepository)
            controller =
                MainSimDataController(
                    usageStateRepository,
                    recoveryController,
                    simIdentityRepository,
                    simLimitsRepository,
                    usageUseCase,
                    dataChangeNotifier,
                    trackingStatusRepository,
                    mainDispatcherRule.testDispatcher,
                    backgroundScope,
                )
            controller.retryShortcuts()
            testScheduler.runCurrent()
            testScheduler.advanceUntilIdle()
            assertEquals(TrackingKey.SHORTCUTS, invokedKey)
        }

    @Test
    fun `retryTraffic does not crash on exception and does not notify`() =
        runTest {
            var invokedKey: TrackingKey? = null
            val fakeStatusRepository =
                object : TrackingStatusRepository {
                    override suspend fun recoverTracking(key: TrackingKey) {
                        invokedKey = key
                        throw IllegalStateException("Recovery failed")
                    }

                    override fun observeTrackingStatus() = flowOf(emptyMap<TrackingKey, TrackingStatus>())
                }
            val recoveryController = TrackingRecoveryController(fakeStatusRepository)
            controller =
                MainSimDataController(
                    usageStateRepository,
                    recoveryController,
                    simIdentityRepository,
                    simLimitsRepository,
                    usageUseCase,
                    dataChangeNotifier,
                    trackingStatusRepository,
                    mainDispatcherRule.testDispatcher,
                    backgroundScope,
                )
            controller.retryTraffic()
            testScheduler.runCurrent()
            testScheduler.advanceUntilIdle()
            assertEquals(TrackingKey.TRAFFIC, invokedKey)
            verify(dataChangeNotifier, never()).notifyDataChanged(any(), any())
        }

    @Test
    fun `retrySimDiscovery does not crash on exception`() =
        runTest {
            var invokedKey: TrackingKey? = null
            val fakeStatusRepository =
                object : TrackingStatusRepository {
                    override suspend fun recoverTracking(key: TrackingKey) {
                        invokedKey = key
                        throw IllegalStateException("Recovery failed")
                    }

                    override fun observeTrackingStatus() = flowOf(emptyMap<TrackingKey, TrackingStatus>())
                }
            val recoveryController = TrackingRecoveryController(fakeStatusRepository)
            controller =
                MainSimDataController(
                    usageStateRepository,
                    recoveryController,
                    simIdentityRepository,
                    simLimitsRepository,
                    usageUseCase,
                    dataChangeNotifier,
                    trackingStatusRepository,
                    mainDispatcherRule.testDispatcher,
                    backgroundScope,
                )
            controller.retrySimDiscovery()
            testScheduler.runCurrent()
            testScheduler.advanceUntilIdle()
            assertEquals(TrackingKey.SIM_DISCOVERY, invokedKey)
        }

    @Test
    fun `recovery cancellation is not suppressed`() =
        runTest {
            var wasCanceled = false
            val fakeStatusRepository =
                object : TrackingStatusRepository {
                    override suspend fun recoverTracking(key: TrackingKey) {
                        try {
                            delay(10_000)
                        } catch (e: CancellationException) {
                            wasCanceled = true
                            throw e
                        }
                    }

                    override fun observeTrackingStatus() = flowOf(emptyMap<TrackingKey, TrackingStatus>())
                }

            val realRecoveryController = TrackingRecoveryController(fakeStatusRepository)

            val appJob = Job()
            val appScope = CoroutineScope(mainDispatcherRule.testDispatcher + appJob)
            val testController =
                MainSimDataController(
                    usageStateRepository,
                    realRecoveryController,
                    simIdentityRepository,
                    simLimitsRepository,
                    usageUseCase,
                    dataChangeNotifier,
                    trackingStatusRepository,
                    mainDispatcherRule.testDispatcher,
                    appScope,
                )

            testController.retryAppSearch()
            testScheduler.runCurrent()
            appJob.cancel()
            testScheduler.advanceUntilIdle()

            assertTrue("Cancellation should be propagated", wasCanceled)
        }

    @Test
    fun `source throwing non IO exception leads to error state with localized string res and stays subscribed`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val sourceFlow =
                flow<List<SimCardUiModel>> {
                    throw IllegalStateException("Fatal source failure")
                }
            whenever(usageStateRepository.observeSimCardUiModels()).thenReturn(sourceFlow)

            controller = createController()
            val uiState = controller.observeUiState(backgroundScope)

            val emittedStates = mutableListOf<SimCardListState>()
            val job = launch { uiState.collect { emittedStates.add(it) } }
            testScheduler.runCurrent()

            val lastState = emittedStates.last()
            assertTrue("Last state should be Error", lastState is SimCardListState.Error)
            assertEquals(R.string.refresh_error, (lastState as SimCardListState.Error).messageRes)

            job.cancel()
        }

    @Test
    fun `retrySimList with recovered source emits loading and then success`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val sourceTrigger = MutableStateFlow(0)
            val simModels = listOf(createTestSimModel())

            val sourceFlow =
                flow {
                    if (sourceTrigger.value == 0) {
                        throw IllegalStateException("Temporary source error")
                    } else {
                        emit(simModels)
                    }
                }
            whenever(usageStateRepository.observeSimCardUiModels()).thenReturn(sourceFlow)

            controller = createController()
            val uiState = controller.observeUiState(backgroundScope)

            val emittedStates = mutableListOf<SimCardListState>()
            val job = launch { uiState.collect { emittedStates.add(it) } }
            testScheduler.runCurrent()

            val errorState = emittedStates.last()
            assertTrue("Should end in Error state initially", errorState is SimCardListState.Error)
            assertEquals(R.string.refresh_error, (errorState as SimCardListState.Error).messageRes)

            sourceTrigger.value = 1
            controller.retrySimList()
            testScheduler.runCurrent()

            assertTrue("Should receive Loading after retry", emittedStates.contains(SimCardListState.Loading))
            val finalState = emittedStates.last()
            assertTrue("Should end in Success state after retry", finalState is SimCardListState.Success)
            assertEquals(simModels, (finalState as SimCardListState.Success).simCards)

            job.cancel()
        }

    @Test
    fun `multiple retrySimList calls do not increase subscription count on source`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val sourceFlow = MutableSharedFlow<List<SimCardUiModel>>(replay = 1)
            whenever(usageStateRepository.observeSimCardUiModels()).thenReturn(sourceFlow)

            controller = createController()
            val uiState = controller.observeUiState(backgroundScope)

            val job = launch { uiState.collect {} }
            testScheduler.runCurrent()

            assertEquals("Single subscription initially", 1, sourceFlow.subscriptionCount.value)

            repeat(5) {
                controller.retrySimList()
                testScheduler.runCurrent()
            }

            assertEquals("Subscription count remains 1 after multiple retries", 1, sourceFlow.subscriptionCount.value)

            job.cancel()
        }

    @Test
    fun `uiState transitions from Empty to Error when SIM_DISCOVERY error occurs`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val modelsFlow = MutableStateFlow(emptyList<SimCardUiModel>())
            val statusFlow = MutableStateFlow<Map<TrackingKey, TrackingStatus>>(emptyMap())
            whenever(usageStateRepository.observeSimCardUiModels()).thenReturn(modelsFlow)
            whenever(usageStateRepository.isDataLoaded()).thenReturn(true)
            whenever(trackingStatusRepository.observeTrackingStatus()).thenReturn(statusFlow)

            controller = createController()
            val uiState = controller.observeUiState(backgroundScope)

            val emittedStates = mutableListOf<SimCardListState>()
            val job = launch { uiState.collect { emittedStates.add(it) } }
            testScheduler.runCurrent()

            assertEquals(SimCardListState.Empty, emittedStates.last())

            statusFlow.value =
                mapOf(TrackingKey.SIM_DISCOVERY to TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN))
            testScheduler.runCurrent()

            val lastState = emittedStates.last()
            assertTrue(lastState is SimCardListState.Error)
            assertEquals(R.string.tracking_banner_sim_discovery_error, (lastState as SimCardListState.Error).messageRes)
            assertEquals(SimCardListState.ErrorKind.SIM_DISCOVERY, lastState.kind)

            job.cancel()
        }

    @Test
    fun `computeSimCardListState handles all outcomes correctly`() {
        val model = createTestSimModel()
        assertEquals(SimCardListState.Success(listOf(model)), computeSimCardListState(listOf(model), true, emptyMap()))
        assertEquals(SimCardListState.Loading, computeSimCardListState(emptyList(), false, emptyMap()))

        val errorState =
            computeSimCardListState(
                emptyList(),
                true,
                mapOf(TrackingKey.SIM_DISCOVERY to TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN)),
            )
        assertTrue(errorState is SimCardListState.Error)
        assertEquals(R.string.tracking_banner_sim_discovery_error, (errorState as SimCardListState.Error).messageRes)
        assertEquals(SimCardListState.ErrorKind.SIM_DISCOVERY, errorState.kind)

        assertEquals(SimCardListState.Empty, computeSimCardListState(emptyList(), true, emptyMap()))
    }

    private fun createTestSimModel(slot: Int = 0) =
        SimCardUiModel(
            slot = slot,
            identity = "sim_$slot",
            operatorName = "Operator",
            iconRes = 0,
            minutesUsed = 0,
            minutesLimit = 100,
            manualAdjustment = 0,
            minutesRemaining = 100,
            effectiveMinutesLimit = 100,
            isMinutesLimitSet = true,
            trafficUsedMb = 0f,
            trafficLimitMb = 1000,
            trafficRemainingGb = 1f,
            effectiveTrafficLimitGb = 1f,
            trafficAdjustmentGb = 0f,
            isTrafficLimitSet = true,
            autoResetEnabled = true,
            autoResetDay = 1,
            rolloverEnabled = false,
        )
}
