package com.github.djbigmuh.minutewatcher.core.domain

import com.github.djbigmuh.minutewatcher.core.model.AppSettings
import com.github.djbigmuh.minutewatcher.core.model.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SettingsUseCaseTest {
    private val appSettingsRepository: AppSettingsRepository = mock()
    private lateinit var settingsUseCase: SettingsUseCase

    @Before
    fun setUp() {
        whenever(appSettingsRepository.observeAppSettings()).thenReturn(MutableStateFlow(AppSettings()))
        settingsUseCase = SettingsUseCase(appSettingsRepository)
    }

    @Test
    fun `appTheme flow maps correct value from repository`() =
        runTest {
            val expectedTheme = AppTheme.DARK
            whenever(
                appSettingsRepository.observeAppSettings(),
            ).thenReturn(MutableStateFlow(AppSettings(theme = expectedTheme)))
            settingsUseCase = SettingsUseCase(appSettingsRepository)

            val actualTheme = settingsUseCase.appTheme.first()

            assertEquals(expectedTheme, actualTheme)
        }

    @Test
    fun `appLanguage flow maps correct value from repository`() =
        runTest {
            val expectedLanguage = "ru"
            whenever(
                appSettingsRepository.observeAppSettings(),
            ).thenReturn(MutableStateFlow(AppSettings(language = expectedLanguage)))
            settingsUseCase = SettingsUseCase(appSettingsRepository)

            val actualLanguage = settingsUseCase.appLanguage.first()

            assertEquals(expectedLanguage, actualLanguage)
        }

    @Test
    fun `setTheme calls updateAppSettings with correct transformation`() =
        runTest {
            val newTheme = AppTheme.LIGHT
            val initialSettings = AppSettings(theme = AppTheme.DARK)

            settingsUseCase.setTheme(newTheme)

            val captor = argumentCaptor<(AppSettings) -> AppSettings>()
            verify(appSettingsRepository).updateAppSettings(captor.capture())

            val updatedSettings = captor.firstValue(initialSettings)
            assertEquals(newTheme, updatedSettings.theme)
        }

    @Test
    fun `setLanguage calls updateAppSettings with correct transformation`() =
        runTest {
            val newLanguage = "en"
            val initialSettings = AppSettings(language = "ru")

            settingsUseCase.setLanguage(newLanguage)

            val captor = argumentCaptor<(AppSettings) -> AppSettings>()
            verify(appSettingsRepository).updateAppSettings(captor.capture())

            val updatedSettings = captor.firstValue(initialSettings)
            assertEquals(newLanguage, updatedSettings.language)
        }
}
