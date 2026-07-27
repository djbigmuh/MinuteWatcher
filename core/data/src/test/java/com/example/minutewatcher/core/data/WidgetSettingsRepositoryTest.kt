package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.domain.SettingsRepository
import com.example.minutewatcher.core.domain.SimRepository
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.AppTheme
import com.example.minutewatcher.core.model.SimSettingsState
import com.example.minutewatcher.core.model.WidgetSettingsState
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class WidgetSettingsRepositoryTest {
    private lateinit var repository: WidgetSettingsRepository
    private val settingsRepository: SettingsRepository = mock()
    private val simRepository: SimRepository = mock()

    @Before
    fun setup() {
        repository = WidgetSettingsRepository(settingsRepository, simRepository)
    }

    @Test
    fun `load should return state populated from app settings`() =
        runTest {
            // Arrange
            val appSettings =
                AppSettings(
                    theme = AppTheme.LIGHT,
                    language = "en",
                    notifyLowMinutes = true,
                    notifyLowTraffic = false,
                    callTrackingEnabled = true,
                    widgetLayoutMode = 1,
                    widgetSimArrangement = 1,
                    widgetTransparency = 80,
                    widgetFontSize = 3,
                    widgetBackgroundColor = 0xFF0000FF.toInt(),
                    migrationV1Completed = true,
                )
            whenever(settingsRepository.getAppSettings()).thenReturn(flowOf(appSettings))
            whenever(simRepository.getIdentity(any())).thenReturn("test_sim")

            val simSettings =
                SimSettingsState(
                    showSim = true,
                    showIcon = true,
                    showName = true,
                    showMinutes = true,
                    showMinutesLimit = false,
                    showTraffic = true,
                    showTrafficLimit = false,
                )
            whenever(simRepository.getSimSettings(any())).thenReturn(flowOf(simSettings))

            // Act
            val result = repository.load()

            // Assert
            assertEquals(1, result.layoutMode)
            assertEquals(1, result.simArrangement)
            assertEquals(80, result.transparency)
            assertEquals(3, result.fontSize)
            assertEquals(0xFF0000FF.toInt(), result.backgroundColor)
            assertEquals(true, result.notifyLowMinutes)
            assertEquals(false, result.notifyLowTraffic)

            assertEquals(2, result.simStates.size)

            verify(settingsRepository).getAppSettings()
            verify(simRepository, times(2)).getSimSettings(any())
        }

    @Test
    fun `save should update general and sim specific settings`() =
        runTest {
            // Arrange
            val state =
                WidgetSettingsState(
                    layoutMode = 2,
                    simArrangement = 0,
                    transparency = 30,
                    fontSize = 1,
                    backgroundColor = 0xFF00FF00.toInt(),
                    notifyLowMinutes = false,
                    notifyLowTraffic = true,
                    callTrackingEnabled = false,
                    simStates =
                        listOf(
                            SimSettingsState(
                                showSim = true,
                                showIcon = false,
                                showName = true,
                                showMinutes = true,
                                showMinutesLimit = false,
                                showTraffic = true,
                                showTrafficLimit = false,
                            ),
                            SimSettingsState(
                                showSim = false,
                                showIcon = true,
                                showName = true,
                                showMinutes = true,
                                showMinutesLimit = false,
                                showTraffic = true,
                                showTrafficLimit = false,
                            ),
                        ),
                )
            whenever(simRepository.getIdentity(0)).thenReturn("sim0")
            whenever(simRepository.getIdentity(1)).thenReturn("sim1")

            // Act
            repository.save(state)

            // Assert
            verify(settingsRepository).updateAppSettings(any())
            verify(simRepository).setSimSettingsBatch(any())
        }
}
