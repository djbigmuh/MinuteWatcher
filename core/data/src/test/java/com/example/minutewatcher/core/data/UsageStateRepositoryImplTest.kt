package com.example.minutewatcher.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.minutewatcher.core.common.util.FixedTimeProvider
import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.data.semantic.SemanticCapabilities
import com.example.minutewatcher.core.data.semantic.SemanticVersion
import com.example.minutewatcher.core.domain.DataRefreshNotifier
import com.example.minutewatcher.core.domain.RefreshResult
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.IdentitySource
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.SimIdentity
import com.example.minutewatcher.core.model.SimUsage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UsageStateRepositoryImplTest {
    private lateinit var context: Context
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()
    private val semanticCapabilities: SemanticCapabilities = mock()
    private val semanticVersion: SemanticVersion = mock()
    private val dataRefreshNotifier: DataRefreshNotifier = mock()
    private val timeProvider = FixedTimeProvider(currentTime = 1000L)

    @Before
    fun setup() {
        initTestLogger()
        context = ApplicationProvider.getApplicationContext()
        whenever(simIdentityRepository.observeActiveSims()).thenReturn(MutableStateFlow(emptyList()))
        whenever(dataRefreshNotifier.refreshFlow).thenReturn(
            MutableSharedFlow<DataRefreshNotifier.RefreshEvent>(replay = 1).apply {
                tryEmit(DataRefreshNotifier.RefreshEvent())
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
                    com.example.minutewatcher.core.model.ActiveSimInfo(
                        identity = SimIdentity("id_0", 1, 0, IdentitySource.ICCID),
                        slotIndex = 0, // Valid
                        subscriptionId = 1,
                        displayName = "SIM 0",
                        carrierName = "Carrier",
                        mccMnc = "12345",
                        isDefaultData = true,
                        isDefaultVoice = true,
                    ),
                    com.example.minutewatcher.core.model.ActiveSimInfo(
                        identity = SimIdentity("id_99", 2, 99, IdentitySource.ICCID),
                        slotIndex = 99, // Invalid
                        subscriptionId = 2,
                        displayName = "SIM 99",
                        carrierName = "Carrier",
                        mccMnc = "12345",
                        isDefaultData = false,
                        isDefaultVoice = false,
                    ),
                )
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(activeSims))

            val usage0 =
                SimUsage(
                    identity = "id_0",
                    slotIndex = 0,
                    subscriptionId = 1,
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
            val usage99 = usage0.copy(identity = "id_99", slotIndex = 99, subscriptionId = 2)

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

            val uiModels = repository.observeSimCardUiModels().first()

            assertEquals("Should only contain valid slot index model", 1, uiModels.size)
            assertEquals(0, uiModels[0].slot)
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

    companion object {
        private const val TIMEOUT_TOLERANCE_MS = 500L
    }
}
