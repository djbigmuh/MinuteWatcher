package com.github.djbigmuh.minutewatcher.feature.main

import com.github.djbigmuh.minutewatcher.core.model.AppTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.verify

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
