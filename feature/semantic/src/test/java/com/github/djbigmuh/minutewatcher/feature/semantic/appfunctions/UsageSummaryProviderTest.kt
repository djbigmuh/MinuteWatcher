package com.github.djbigmuh.minutewatcher.feature.semantic.appfunctions

import androidx.appfunctions.AppFunctionElementNotFoundException
import com.github.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import com.github.djbigmuh.minutewatcher.core.domain.RefreshResult
import com.github.djbigmuh.minutewatcher.core.domain.UsageStateRepository
import com.github.djbigmuh.minutewatcher.core.model.Constants
import com.github.djbigmuh.minutewatcher.core.model.DataSourceType
import com.github.djbigmuh.minutewatcher.core.model.GlobalSnapshot
import com.github.djbigmuh.minutewatcher.core.model.MeasurementUnit
import com.github.djbigmuh.minutewatcher.core.model.PackageType
import com.github.djbigmuh.minutewatcher.core.model.SchemaVersion
import com.github.djbigmuh.minutewatcher.core.model.SemanticEntity
import com.github.djbigmuh.minutewatcher.core.model.SimSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class UsageSummaryProviderTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val testDispatcher = StandardTestDispatcher()

    private class FakeUsageStateRepository(
        initialSnapshot: GlobalSnapshot,
        private val refreshAction: suspend () -> RefreshResult = { RefreshResult.Success(initialSnapshot) },
    ) : UsageStateRepository {
        val snapshotFlow = MutableStateFlow(initialSnapshot)
        var refreshCallCount = 0

        override fun observeSimCardUiModels(): Flow<List<com.github.djbigmuh.minutewatcher.core.model.SimCardUiModel>> =
            flowOf(emptyList())

        override fun observeSnapshot(): StateFlow<GlobalSnapshot> = snapshotFlow

        override fun isDataLoaded(): Boolean = true

        override suspend fun refresh(): RefreshResult {
            refreshCallCount++
            return refreshAction()
        }
    }

    @Test
    fun `getUsageSummary returns data without refresh when snapshot is already populated`() =
        runTest(testDispatcher) {
            val entity =
                SemanticEntity(
                    id = "0_m",
                    simSlot = 0,
                    operatorName = "MTS",
                    packageType = PackageType.MINUTES,
                    used = 10L,
                    limit = 100L,
                    remaining = 90L,
                    unit = MeasurementUnit.MINUTES,
                    periodStart = 0L,
                    periodEnd = 5000L,
                    lastUpdateTime = 0L,
                    sourceType = DataSourceType.SYSTEM,
                )
            val sim = SimSnapshot(0, "sim0", "MTS", mapOf(PackageType.MINUTES to entity), 1)
            val snapshot = GlobalSnapshot(SchemaVersion(1, 0, 0), 12345L, listOf(sim), emptyList())

            val fakeRepo = FakeUsageStateRepository(snapshot)
            val provider = UsageSummaryProvider(fakeRepo)

            withTimeout(5.seconds) {
                val summary = provider.getUsageSummary()
                assertEquals(12345L, summary.snapshotTimestampMillis)
                assertEquals(1, summary.sims.size)
                assertEquals(0, fakeRepo.refreshCallCount)
            }
        }

    @Test
    fun `getUsageSummary refreshes and returns summary when snapshot is empty initially`() =
        runTest(testDispatcher) {
            val emptySnapshot =
                GlobalSnapshot(SchemaVersion(1, 0, 0), Constants.Data.EMPTY_TIMESTAMP, emptyList(), emptyList())
            val filledSim = SimSnapshot(0, "sim0", "MTS", emptyMap(), 1)
            val filledSnapshot = GlobalSnapshot(SchemaVersion(1, 0, 0), 5555L, listOf(filledSim), emptyList())

            lateinit var fakeRepo: FakeUsageStateRepository
            fakeRepo =
                FakeUsageStateRepository(emptySnapshot) {
                    fakeRepo.snapshotFlow.value = filledSnapshot
                    RefreshResult.Success(filledSnapshot)
                }
            val provider = UsageSummaryProvider(fakeRepo)

            withTimeout(5.seconds) {
                val summary = provider.getUsageSummary()
                assertEquals(5555L, summary.snapshotTimestampMillis)
                assertEquals(1, fakeRepo.refreshCallCount)
            }
        }

    @Test
    fun `getUsageSummary throws when snapshot remains empty after refresh`() =
        runTest(testDispatcher) {
            val emptySnapshot =
                GlobalSnapshot(SchemaVersion(1, 0, 0), Constants.Data.EMPTY_TIMESTAMP, emptyList(), emptyList())
            val fakeRepo =
                FakeUsageStateRepository(emptySnapshot) {
                    RefreshResult.Timeout(emptySnapshot)
                }
            val provider = UsageSummaryProvider(fakeRepo)

            val failure = runCatching { provider.getUsageSummary() }.exceptionOrNull()
            assertTrue(failure is AppFunctionElementNotFoundException)
        }

    @Test
    fun `getUsageSummary throws when snapshot has no active sims`() =
        runTest(testDispatcher) {
            val emptySimsSnapshot = GlobalSnapshot(SchemaVersion(1, 0, 0), 123L, emptyList(), emptyList())
            val fakeRepo = FakeUsageStateRepository(emptySimsSnapshot)
            val provider = UsageSummaryProvider(fakeRepo)

            val failure = runCatching { provider.getUsageSummary() }.exceptionOrNull()
            assertTrue(failure is AppFunctionElementNotFoundException)
        }

    @Test
    fun `getUsageSummary throws when refresh throws regular exception`() =
        runTest(testDispatcher) {
            val emptySnapshot =
                GlobalSnapshot(SchemaVersion(1, 0, 0), Constants.Data.EMPTY_TIMESTAMP, emptyList(), emptyList())
            val fakeRepo =
                FakeUsageStateRepository(emptySnapshot) {
                    throw IllegalStateException("Network down")
                }
            val provider = UsageSummaryProvider(fakeRepo)

            val failure = runCatching { provider.getUsageSummary() }.exceptionOrNull()
            assertTrue(failure is AppFunctionElementNotFoundException)
        }

    @Test
    fun `getUsageSummary propagates cancellation exception from refresh`() =
        runTest(testDispatcher) {
            val emptySnapshot =
                GlobalSnapshot(SchemaVersion(1, 0, 0), Constants.Data.EMPTY_TIMESTAMP, emptyList(), emptyList())
            val fakeRepo =
                FakeUsageStateRepository(emptySnapshot) {
                    throw CancellationException("Canceled")
                }
            val provider = UsageSummaryProvider(fakeRepo)

            val failure = runCatching { provider.getUsageSummary() }.exceptionOrNull()
            assertTrue(failure is CancellationException)
        }

    @Test
    fun `mapSnapshot correctly converts snapshot into usage summary with sorting and units`() =
        runTest(testDispatcher) {
            val sim1Minutes =
                SemanticEntity(
                    id = "1_m",
                    simSlot = 1,
                    operatorName = "Beeline",
                    packageType = PackageType.MINUTES,
                    used = 20L,
                    limit = 200L,
                    remaining = 180L,
                    unit = MeasurementUnit.MINUTES,
                    periodStart = 0L,
                    periodEnd = 1000L,
                    lastUpdateTime = 0L,
                    sourceType = DataSourceType.SYSTEM,
                )
            val sim0Data =
                SemanticEntity(
                    id = "0_d",
                    simSlot = 0,
                    operatorName = "MTS",
                    packageType = PackageType.DATA,
                    used = 0L,
                    limit = Constants.BYTES_PER_GB * 10L,
                    remaining = Constants.BYTES_PER_GB * 10L,
                    unit = MeasurementUnit.BYTES,
                    periodStart = 0L,
                    periodEnd = 2000L,
                    lastUpdateTime = 0L,
                    sourceType = DataSourceType.SYSTEM,
                )

            val sim1 = SimSnapshot(1, "sim1", "Beeline", mapOf(PackageType.MINUTES to sim1Minutes), 1)
            val sim0 = SimSnapshot(0, "sim0", "MTS", mapOf(PackageType.DATA to sim0Data), 1)

            val snapshot = GlobalSnapshot(SchemaVersion(1, 0, 0), 999L, listOf(sim1, sim0), emptyList())

            val provider = UsageSummaryProvider(mock())

            withTimeout(5.seconds) {
                val summary = provider.mapSnapshot(snapshot)
                assertEquals(2, summary.sims.size)
                assertEquals(0, summary.sims[0].slot)
                assertEquals("MTS", summary.sims[0].operatorName)
                assertFalse(summary.sims[0].minutesLimitSet)
                assertTrue(summary.sims[0].dataLimitSet)
                assertEquals(10.0, summary.sims[0].dataRemainingGb, 0.001)
                assertEquals(2000L, summary.sims[0].periodEndMillis)

                assertEquals(1, summary.sims[1].slot)
                assertEquals("Beeline", summary.sims[1].operatorName)
                assertTrue(summary.sims[1].minutesLimitSet)
                assertEquals(180L, summary.sims[1].minutesRemaining)
                assertEquals(200L, summary.sims[1].minutesLimit)
                assertFalse(summary.sims[1].dataLimitSet)
                assertEquals(0.0, summary.sims[1].dataRemainingGb, 0.001)
                assertEquals(1000L, summary.sims[1].periodEndMillis)
            }
        }

    @Test
    fun `mapSnapshot sets periodEndMillis to zero when sim has no entities`() =
        runTest(testDispatcher) {
            val sim = SimSnapshot(0, "sim0", "MTS", emptyMap(), 1)
            val snapshot = GlobalSnapshot(SchemaVersion(1, 0, 0), 999L, listOf(sim), emptyList())

            val provider = UsageSummaryProvider(mock())

            withTimeout(5.seconds) {
                val summary = provider.mapSnapshot(snapshot)
                assertEquals(1, summary.sims.size)
                assertEquals(0L, summary.sims[0].periodEndMillis)
            }
        }
}
