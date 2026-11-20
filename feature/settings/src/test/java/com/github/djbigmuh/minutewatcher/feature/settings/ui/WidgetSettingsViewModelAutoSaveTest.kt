package com.github.djbigmuh.minutewatcher.feature.settings.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetSettingsViewModelAutoSaveTest : BaseWidgetSettingsViewModelFixture() {
    @Test
    fun `auto-save triggers after throttle delay`() =
        runTest {
            initViewModel()

            viewModel.updateSettings { it.copy(transparency = 10) }
            runCurrent()

            verify(saver, never()).save(any(), any())

            advanceTimeBy(3.seconds)
            runCurrent()

            verify(saver).save(any(), any())
        }

    @Test
    fun `auto-save combines multiple updates`() =
        runTest {
            initViewModel()

            viewModel.updateSettings { it.copy(transparency = 10) }
            viewModel.updateSettings { it.copy(fontSize = 1) }
            viewModel.updateSettings { it.copy(layoutMode = 2) }

            advanceTimeBy(3.seconds)
            runCurrent()

            verify(saver, times(1)).save(any(), any())
        }

    @Test
    fun `auto-save cancels pending save on manual save`() =
        runTest {
            initViewModel()

            viewModel.updateSettings { it.copy(transparency = 10) }
            runCurrent()

            viewModel.saveAndNotify()
            advanceUntilIdle()

            verify(saver, times(1)).save(any(), any())

            advanceTimeBy(3.seconds)
            runCurrent()

            verify(saver, times(1)).save(any(), any())
        }

    @Test
    fun `heavy load of updates does not crash`() =
        runTest {
            initViewModel()
            repeat(100) { i ->
                viewModel.updateSettings { it.copy(transparency = i % 100) }
            }
            advanceTimeBy(3.seconds)
            runCurrent()
            verify(saver, atLeastOnce()).save(any(), any())
        }

    @Test
    fun `auto-save correctly uses most recent state`() =
        runTest {
            initViewModel()

            viewModel.updateSettings { it.copy(transparency = 10) }
            advanceTimeBy(1.seconds)
            viewModel.updateSettings { it.copy(transparency = 20) }
            advanceTimeBy(1.seconds)
            viewModel.updateSettings { it.copy(transparency = 30) }

            advanceTimeBy(3.seconds)
            runCurrent()

            verify(saver).save(argThat { transparency == 30 }, any())
        }
}
