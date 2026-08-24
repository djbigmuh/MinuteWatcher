package com.example.minutewatcher.feature.main

import com.example.minutewatcher.core.model.AppTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelSettingsTest : MainViewModelTestFixtures() {
    @Test
    fun `setTheme calls settingsUseCase`() =
        testScope.runTest {
            val viewModel = createViewModel(backgroundScope)
            val theme = AppTheme.DARK

            viewModel.setTheme(theme)
            runCurrent()
            advanceUntilIdle()

            verify(settingsUseCase).setTheme(theme)
        }
}
