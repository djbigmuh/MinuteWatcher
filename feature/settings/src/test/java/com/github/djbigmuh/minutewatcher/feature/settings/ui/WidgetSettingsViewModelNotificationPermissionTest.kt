package com.github.djbigmuh.minutewatcher.feature.settings.ui

import com.github.djbigmuh.minutewatcher.core.model.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verifyBlocking

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetSettingsViewModelNotificationPermissionTest : BaseWidgetSettingsViewModelFixture() {
    @Test
    fun `notification permission request is persisted`() =
        runTest {
            initViewModel()

            viewModel.setNotificationPermissionRequested()
            advanceUntilIdle()

            val captor = argumentCaptor<(AppSettings) -> AppSettings>()
            verifyBlocking(appSettingsRepository) {
                updateAppSettings(captor.capture())
            }

            assertTrue(captor.firstValue(AppSettings()).notificationPermissionRequested)
        }

    @Test
    fun `permanent notification permission denial is persisted`() =
        runTest {
            initViewModel()

            viewModel.setNotificationPermissionPermanentlyDenied(true)
            advanceUntilIdle()

            val captor = argumentCaptor<(AppSettings) -> AppSettings>()
            verifyBlocking(appSettingsRepository) {
                updateAppSettings(captor.capture())
            }

            val updated = captor.firstValue(AppSettings())
            assertTrue(updated.notificationPermissionRequested)
            assertTrue(updated.notificationPermissionPermanentlyDenied)
        }
}
