package com.github.djbigmuh.minutewatcher.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.github.djbigmuh.minutewatcher.core.common.util.FixedTimeProvider
import com.github.djbigmuh.minutewatcher.core.common.util.initTestLogger
import com.github.djbigmuh.minutewatcher.core.data.semantic.SemanticCapabilities
import com.github.djbigmuh.minutewatcher.core.data.semantic.SemanticVersion
import com.github.djbigmuh.minutewatcher.core.domain.DataRefreshNotifier
import com.github.djbigmuh.minutewatcher.core.domain.RefreshResult
import com.github.djbigmuh.minutewatcher.core.domain.SimIdentityRepository
import com.github.djbigmuh.minutewatcher.core.domain.SimUsageRepository
import com.github.djbigmuh.minutewatcher.core.model.ActiveSimInfo
import com.github.djbigmuh.minutewatcher.core.model.Constants
import com.github.djbigmuh.minutewatcher.core.model.IdentitySource
import com.github.djbigmuh.minutewatcher.core.model.SchemaVersion
import com.github.djbigmuh.minutewatcher.core.model.SimCardUiModel
import com.github.djbigmuh.minutewatcher.core.model.SimIdentity
import com.github.djbigmuh.minutewatcher.core.model.SimUsage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class UsageStateRepositoryImplTest {
    private lateinit var context: Context
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()
    private val semanticCapabilities: SemanticCapabilities = mock()
    private val semanticVersion: SemanticVersion = mock()
    private val dataRefreshNotifier: DataRefreshNotifier = mock()
    private val timeProvider = FixedTimeProvider(currentTime = 1000L)

    private val resolvedSimsFlow = MutableStateFlow<List<ActiveSimInfo>?>(emptyList())

    @Before
    fun setup() {
        initTestLogger()
        context = ApplicationProvider.getApplicationContext()
        resolvedSimsFlow.value = emptyList()
        whenever(simIdentityRepository.observeResolvedActiveSims()).thenReturn(resolvedSimsFlow)
        whenever(simIdentityRepository.observeActiveSims()).thenReturn(MutableStateFlow(emptyList()))
        whenever(dataRefreshNotifier.refreshFlow).thenReturn(
            MutableSharedFlow<DataRefreshNotifier.RefreshEvent>(replay = 1).apply {
                tryEmit(DataRefreshNotifier.RefreshEvent(eventId = 1L))
            },
        )
        whenever(semanticVersion.getVersion()).thenReturn(SchemaVersion(1, 0, 0))
        whenever(semanticCapabilities.getCapabilities()).thenReturn(emptyList())
    }

    @Test
    fun `observeSimCardUiModels filters out invalid slot indices`() =
        runTest {
            val activeSims =
                listOf(
                    createActiveSimInfo("id_0", subId = 1, slot = 0), // Valid
                    createActiveSimInfo("id_99", subId = 2, slot = 99), // Invalid
                )
            resolvedSimsFlow.value = activeSims

            val usage0 = createSimUsage("id_0", slot = 0, subId = 1)
            val usage99 = createSimUsage("id_99", slot = 99, subId = 2)

            whenever(simUsageRepository.getSimUsage("id_0")).thenReturn(flowOf(usage0))
            whenever(simUsageRepository.getSimUsage("id_99")).thenReturn(flowOf(usage99))

            val repository =
                UsageStateRepositoryImpl(
                    context = context,
                    deps =
                        UsageRepositoryDependencies(
                            simIdentityRepository = simIdentityRepository,
                            simUsageRepository = simUsageRepository,
                            semanticCapabilities = semanticCapabilities,
                            semanticVersion = semanticVersion,
                            dataRefreshNotifier = dataRefreshNotifier,
                            timeProvider = timeProvider,
                        ),
                    scope = backgroundScope,
                )

            val uiModelsFlow = repository.observeSimCardUiModels()
            testScheduler.advanceTimeBy(Constants.Flow.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            val uiModels = uiModelsFlow.first()

            assertEquals("Should only contain valid slot index model", 1, uiModels.size)
            assertEquals(0, uiModels[0].slot)
        }

    @Test
    fun `discovery completed with empty list sets isDataLoaded to true and snapshot timestamp to valid`() =
        runTest {
            resolvedSimsFlow.value = emptyList()

            val repository =
                UsageStateRepositoryImpl(
                    context = context,
                    deps =
                        UsageRepositoryDependencies(
                            simIdentityRepository = simIdentityRepository,
                            simUsageRepository = simUsageRepository,
                            semanticCapabilities = semanticCapabilities,
                            semanticVersion = semanticVersion,
                            dataRefreshNotifier = dataRefreshNotifier,
                            timeProvider = timeProvider,
                        ),
                    scope = backgroundScope,
                )

            testScheduler.advanceTimeBy(Constants.Flow.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            assertTrue("isDataLoaded should be true after empty discovery result", repository.isDataLoaded())
            val snapshot = repository.observeSnapshot().value
            assertNotEquals(
                "Snapshot timestamp should not be EMPTY_TIMESTAMP after load",
                Constants.Data.EMPTY_TIMESTAMP,
                snapshot.timestamp,
            )
            assertEquals(1000L, snapshot.timestamp)
        }

    @Test
    fun `before discovery completes isDataLoaded is false and snapshot has EMPTY_TIMESTAMP`() =
        runTest {
            resolvedSimsFlow.value = null

            val repository =
                UsageStateRepositoryImpl(
                    context = context,
                    deps =
                        UsageRepositoryDependencies(
                            simIdentityRepository = simIdentityRepository,
                            simUsageRepository = simUsageRepository,
                            semanticCapabilities = semanticCapabilities,
                            semanticVersion = semanticVersion,
                            dataRefreshNotifier = dataRefreshNotifier,
                            timeProvider = timeProvider,
                        ),
                    scope = backgroundScope,
                )

            testScheduler.advanceTimeBy(Constants.Flow.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            assertFalse("isDataLoaded should be false before discovery resolves", repository.isDataLoaded())
            val snapshot = repository.observeSnapshot().value
            assertEquals(
                "Snapshot timestamp should be EMPTY_TIMESTAMP before load",
                Constants.Data.EMPTY_TIMESTAMP,
                snapshot.timestamp,
            )
        }

    @Test
    fun `discovery returned SIMs but usages not yet emitted keeps isDataLoaded false`() =
        runTest {
            val usageFlow = MutableSharedFlow<SimUsage>()
            val id = "id_0"
            val sims = listOf(createActiveSimInfo(id, subId = 1, slot = 0))
            whenever(simUsageRepository.getSimUsage(id)).thenReturn(usageFlow)

            resolvedSimsFlow.value = sims

            val repository =
                UsageStateRepositoryImpl(
                    context = context,
                    deps =
                        UsageRepositoryDependencies(
                            simIdentityRepository = simIdentityRepository,
                            simUsageRepository = simUsageRepository,
                            semanticCapabilities = semanticCapabilities,
                            semanticVersion = semanticVersion,
                            dataRefreshNotifier = dataRefreshNotifier,
                            timeProvider = timeProvider,
                        ),
                    scope = backgroundScope,
                )

            testScheduler.advanceTimeBy(Constants.Flow.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            assertFalse("isDataLoaded should be false until first usage emission", repository.isDataLoaded())

            usageFlow.emit(createSimUsage(id, slot = 0, subId = 1))
            testScheduler.runCurrent()

            assertTrue("isDataLoaded should be true after usage emission", repository.isDataLoaded())
        }

    @Test
    fun `flag transition to true with empty list triggers new emissions`() =
        runTest {
            resolvedSimsFlow.value = null

            val repository =
                UsageStateRepositoryImpl(
                    context = context,
                    deps =
                        UsageRepositoryDependencies(
                            simIdentityRepository = simIdentityRepository,
                            simUsageRepository = simUsageRepository,
                            semanticCapabilities = semanticCapabilities,
                            semanticVersion = semanticVersion,
                            dataRefreshNotifier = dataRefreshNotifier,
                            timeProvider = timeProvider,
                        ),
                    scope = backgroundScope,
                )

            val uiModelsEmissions = mutableListOf<List<SimCardUiModel>>()
            val uiJob = launch { repository.observeSimCardUiModels().collect { uiModelsEmissions.add(it) } }

            testScheduler.advanceTimeBy(Constants.Flow.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            resolvedSimsFlow.value = emptyList()
            testScheduler.advanceTimeBy(Constants.Flow.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            assertTrue("Should have emitted uiModels after resolution", uiModelsEmissions.isNotEmpty())
            assertTrue("isDataLoaded should be true", repository.isDataLoaded())

            uiJob.cancel()
        }

    @Test
    fun `refresh finishes within timeout when snapshot does not update`() =
        runTest {
            // We use a mock that doesn't emit to refreshFlow, so snapshotFlow will never update
            whenever(dataRefreshNotifier.refreshFlow).thenReturn(MutableSharedFlow())

            val repository =
                UsageStateRepositoryImpl(
                    context = context,
                    deps =
                        UsageRepositoryDependencies(
                            simIdentityRepository = simIdentityRepository,
                            simUsageRepository = simUsageRepository,
                            semanticCapabilities = semanticCapabilities,
                            semanticVersion = semanticVersion,
                            dataRefreshNotifier = dataRefreshNotifier,
                            timeProvider = timeProvider,
                        ),
                    scope = backgroundScope,
                )

            val startTime = testScheduler.currentTime
            val result = repository.refresh()
            val endTime = testScheduler.currentTime

            assertTrue("Result should be Timeout, was $result", result is RefreshResult.Timeout)
            val duration = endTime - startTime
            // SNAPSHOT_TIMEOUT_MS is 2000ms.
            assertTrue(
                "Refresh should finish within timeout (duration: $duration)",
                duration <=
                    Constants.Data.SNAPSHOT_TIMEOUT_MS +
                    TIMEOUT_TOLERANCE_MS,
            )
            assertTrue(
                "Refresh should take at least the timeout duration (duration: $duration)",
                duration >=
                    Constants.Data.SNAPSHOT_TIMEOUT_MS,
            )
        }

    @Test
    fun `getSimUsage is collected only once per SIM despite multiple public flow observers`() =
        runTest {
            val id = "id_0"
            val sims = listOf(createActiveSimInfo(id, subId = 1, slot = 0))
            resolvedSimsFlow.value = sims

            var collectionCount = 0
            val usageFlow =
                flow {
                    collectionCount++
                    emit(createSimUsage(id, slot = 0, subId = 1))
                }
            whenever(simUsageRepository.getSimUsage(id)).thenReturn(usageFlow)

            val repository =
                UsageStateRepositoryImpl(
                    context = context,
                    deps =
                        UsageRepositoryDependencies(
                            simIdentityRepository = simIdentityRepository,
                            simUsageRepository = simUsageRepository,
                            semanticCapabilities = semanticCapabilities,
                            semanticVersion = semanticVersion,
                            dataRefreshNotifier = dataRefreshNotifier,
                            timeProvider = timeProvider,
                        ),
                    scope = backgroundScope,
                )

            // Start multiple observers
            val job1 = launch { repository.observeSnapshot().collect {} }
            val job2 = launch { repository.observeSimCardUiModels().collect {} }

            testScheduler.advanceTimeBy(Constants.Flow.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            assertEquals("Should be collected exactly once per SIM", 1, collectionCount)

            job1.cancel()
            job2.cancel()
        }

    @Test
    fun `refresh returns Success even if wall-clock time does not advance`() =
        runTest {
            val refreshEvents = MutableSharedFlow<DataRefreshNotifier.RefreshEvent>(replay = 1)
            refreshEvents.emit(DataRefreshNotifier.RefreshEvent(eventId = 1L))
            whenever(dataRefreshNotifier.refreshFlow).thenReturn(refreshEvents)

            val repository =
                UsageStateRepositoryImpl(
                    context = context,
                    deps =
                        UsageRepositoryDependencies(
                            simIdentityRepository = simIdentityRepository,
                            simUsageRepository = simUsageRepository,
                            semanticCapabilities = semanticCapabilities,
                            semanticVersion = semanticVersion,
                            dataRefreshNotifier = dataRefreshNotifier,
                            timeProvider = timeProvider,
                        ),
                    scope = backgroundScope,
                )

            // Trigger initialization
            backgroundScope.launch { repository.observeSnapshot().collect {} }
            testScheduler.advanceTimeBy(Constants.Flow.DEBOUNCE_MS * 2)
            testScheduler.runCurrent()

            // 1. Initial state
            val firstSnapshot = repository.observeSnapshot().value
            assertEquals(1000L, firstSnapshot.timestamp)

            // 2. Perform refresh. We mock notifyRefresh to emit a NEW event to refreshFlow.
            // Our FixedTimeProvider stays at 1000L.
            whenever(dataRefreshNotifier.notifyRefresh(any(), any())).thenAnswer {
                refreshEvents.tryEmit(DataRefreshNotifier.RefreshEvent(eventId = 2L))
            }

            val result = repository.refresh()

            assertTrue(
                "Result should be Success even with identical timestamp, was $result",
                result is RefreshResult.Success,
            )
            val newSnapshot = (result as RefreshResult.Success).snapshot
            assertEquals(1000L, newSnapshot.timestamp)
            assertTrue("Revision must have advanced", newSnapshot.revision > firstSnapshot.revision)
        }

    private fun createActiveSimInfo(
        id: String,
        subId: Int,
        slot: Int,
    ) = ActiveSimInfo(
        identity = SimIdentity(id, subId, slot, IdentitySource.ICCID, isStable = true),
        slotIndex = slot,
        subscriptionId = subId,
        iccId = id,
        displayName = "SIM $slot",
        carrierName = "Carrier",
        isDefaultData = (slot == 0),
        isDefaultVoice = (slot == 0),
    )

    private fun createSimUsage(
        id: String,
        slot: Int,
        subId: Int,
    ) = SimUsage(
        identity = id,
        slotIndex = slot,
        subscriptionId = subId,
        operator = "Op",
        minutesUsed = 0,
        minutesLimit = 0,
        trafficUsedMb = 0f,
        trafficLimitMb = 0,
        manualAdjustment = 0,
        trafficAdjustmentGb = 0f,
        autoReset = false,
        autoResetDay = 1,
        lastResetDate = 0L,
        rolloverEnabled = false,
    )

    companion object {
        private const val TIMEOUT_TOLERANCE_MS = 500L
    }
}
