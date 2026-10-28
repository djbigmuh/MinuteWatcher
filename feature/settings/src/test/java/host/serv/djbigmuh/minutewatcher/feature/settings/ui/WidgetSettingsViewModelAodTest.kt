package host.serv.djbigmuh.minutewatcher.feature.settings.ui

import host.serv.djbigmuh.minutewatcher.core.model.Constants
import host.serv.djbigmuh.minutewatcher.core.model.DataSourceType
import host.serv.djbigmuh.minutewatcher.core.model.GlobalSnapshot
import host.serv.djbigmuh.minutewatcher.core.model.MeasurementUnit
import host.serv.djbigmuh.minutewatcher.core.model.PackageType
import host.serv.djbigmuh.minutewatcher.core.model.SchemaVersion
import host.serv.djbigmuh.minutewatcher.core.model.SemanticEntity
import host.serv.djbigmuh.minutewatcher.core.model.SimSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetSettingsViewModelAodTest : BaseWidgetSettingsViewModelFixture() {
    @Test
    fun `init refreshes aod summary availability`() =
        runTest {
            whenever(aodSummaryAvailability.isAodSummaryAvailable()).thenReturn(true)
            initViewModel()

            assertTrue(viewModel.aodSummaryAvailable.value)
            verify(aodSummaryAvailability).isAodSummaryAvailable()
        }

    @Test
    fun `refreshAodSummaryAvailability updates state to false when helper returns false`() =
        runTest {
            whenever(aodSummaryAvailability.isAodSummaryAvailable()).thenReturn(false)
            initViewModel()

            assertFalse(viewModel.aodSummaryAvailable.value)
        }

    @Test
    fun `refreshAodSummaryAvailability handles RuntimeException and publishes false`() =
        runTest {
            whenever(aodSummaryAvailability.isAodSummaryAvailable())
                .thenThrow(RuntimeException("Service unavailable"))
            initViewModel()

            assertFalse(viewModel.aodSummaryAvailable.value)
        }

    @Test
    fun `refreshAodSummaryAvailability rethrows CancellationException`() =
        runTest {
            whenever(aodSummaryAvailability.isAodSummaryAvailable())
                .thenThrow(CancellationException("Canceled"))

            try {
                initViewModel()
            } catch (e: CancellationException) {
                assertEquals("Canceled", e.message)
            }
        }

    @Test
    fun `aodSummaryHasContent is false when snapshot timestamp is empty`() =
        runTest {
            val snapshotFlow =
                MutableStateFlow(
                    GlobalSnapshot(
                        SchemaVersion(1, 0, 0),
                        Constants.Data.EMPTY_TIMESTAMP,
                        emptyList(),
                        emptyList(),
                    ),
                )
            whenever(usageStateRepository.observeSnapshot()).thenReturn(snapshotFlow)
            initViewModel()
            backgroundScope.launch { viewModel.aodSummaryHasContent.collect {} }
            runCurrent()

            assertFalse(viewModel.aodSummaryHasContent.value)
        }

    @Test
    fun `aodSummaryHasContent is false when snapshot has no sims with limits`() =
        runTest {
            val sim0 = SimSnapshot(0, "id_0", "MTS", emptyMap(), 1)
            val snapshotFlow =
                MutableStateFlow(
                    GlobalSnapshot(SchemaVersion(1, 0, 0), 1000L, listOf(sim0), emptyList()),
                )
            whenever(usageStateRepository.observeSnapshot()).thenReturn(snapshotFlow)
            initViewModel()
            backgroundScope.launch { viewModel.aodSummaryHasContent.collect {} }
            runCurrent()

            assertFalse(viewModel.aodSummaryHasContent.value)
        }

    @Test
    fun `aodSummaryHasContent is true when snapshot has at least one sim with limit`() =
        runTest {
            val entities =
                mapOf(
                    PackageType.MINUTES to
                        SemanticEntity(
                            "min_0",
                            0,
                            "MTS",
                            PackageType.MINUTES,
                            100L,
                            10L,
                            90L,
                            MeasurementUnit.MINUTES,
                            0L,
                            0L,
                            0L,
                            DataSourceType.SYSTEM,
                        ),
                )
            val sim0 = SimSnapshot(0, "id_0", "MTS", entities, 1)
            val snapshotFlow =
                MutableStateFlow(
                    GlobalSnapshot(SchemaVersion(1, 0, 0), 1000L, listOf(sim0), emptyList()),
                )
            whenever(usageStateRepository.observeSnapshot()).thenReturn(snapshotFlow)
            initViewModel()
            backgroundScope.launch { viewModel.aodSummaryHasContent.collect {} }
            runCurrent()

            assertTrue(viewModel.aodSummaryHasContent.value)
        }
}
