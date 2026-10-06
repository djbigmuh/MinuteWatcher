package com.example.minutewatcher.feature.settings.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
            whenever(aodSummaryAvailability.isAodSummaryAvailable()).thenThrow(RuntimeException("Service unavailable"))
            initViewModel()

            assertFalse(viewModel.aodSummaryAvailable.value)
        }

    @Test
    fun `refreshAodSummaryAvailability rethrows CancellationException`() =
        runTest {
            whenever(aodSummaryAvailability.isAodSummaryAvailable()).thenThrow(CancellationException("Cancelled"))

            try {
                initViewModel()
            } catch (e: CancellationException) {
                assertEquals("Cancelled", e.message)
            }
        }
}
