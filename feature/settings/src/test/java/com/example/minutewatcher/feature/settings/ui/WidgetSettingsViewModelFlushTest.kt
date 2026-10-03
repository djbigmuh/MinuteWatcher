package com.example.minutewatcher.feature.settings.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetSettingsViewModelFlushTest : BaseWidgetSettingsViewModelTest() {
    @Test
    fun `onCleared flushes unsaved changes when no prior save occurred`() =
        runTest {
            whenever(saver.save(any(), any())).thenReturn(SaveResult.Failed(SaveFailureReason.GENERAL))
            initViewModel()

            viewModel.updateSettings { it.copy(transparency = 75) }
            runCurrent()

            viewModel.onClearedForTest()
            testScheduler.advanceTimeBy(1.seconds)
            runCurrent()
            advanceUntilIdle()

            verify(saver).save(argThat { transparency == 75 }, any())
        }

    @Test
    fun `onCleared skips flush when settings have no changes`() =
        runTest {
            initViewModel()

            viewModel.onClearedForTest()
            advanceUntilIdle()

            verify(saver, never()).save(any(), any())
        }

    @Test
    fun `onCleared skips flush when changes were already saved`() =
        runTest {
            initViewModel()

            viewModel.updateSettings { it.copy(transparency = 75) }
            advanceTimeBy(3.seconds)
            runCurrent()

            verify(saver, times(1)).save(argThat { transparency == 75 }, any())

            viewModel.onClearedForTest()
            advanceUntilIdle()

            verify(saver, times(1)).save(any(), any())
        }

    @Test
    fun `onCleared flushes new changes after previous successful save`() =
        runTest {
            initViewModel()

            viewModel.updateSettings { it.copy(transparency = 40) }
            advanceTimeBy(3.seconds)
            runCurrent()

            verify(saver, times(1)).save(argThat { transparency == 40 }, any())

            viewModel.updateSettings { it.copy(transparency = 90) }
            runCurrent()

            viewModel.onClearedForTest()
            testScheduler.advanceTimeBy(1.seconds)
            runCurrent()
            advanceUntilIdle()

            verify(saver).save(argThat { transparency == 90 }, any())
        }
}
