package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimWidgetVisibilityRepository
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
    private val settingsRepository: AppSettingsRepository = mock()
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simWidgetVisibilityRepository: SimWidgetVisibilityRepository = mock()

    @Before
    fun setup() {
        repository = WidgetSettingsRepository(settingsRepository, simIdentityRepository, simWidgetVisibilityRepository)
    }

    @Test
    fun `load should return state populated from app settings`() =
        runTest {
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
            val appSettingsFlow = flowOf(appSettings)
            whenever(settingsRepository.getAppSettings()).thenReturn(appSettingsFlow)
            whenever(simIdentityRepository.getIdentity(any())).thenReturn("test_sim")

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
            val simSettingsFlow = flowOf(simSettings)
            whenever(simWidgetVisibilityRepository.getSimSettings(any())).thenReturn(simSettingsFlow)

            val result = repository.load()

            assertEquals(1, result.layoutMode)
            assertEquals(1, result.simArrangement)
            assertEquals(80, result.transparency)
            assertEquals(3, result.fontSize)
            assertEquals(0xFF0000FF.toInt(), result.backgroundColor)
            assertEquals(true, result.notifyLowMinutes)
            assertEquals(false, result.notifyLowTraffic)

            assertEquals(2, result.simStates.size)

            verify(settingsRepository).getAppSettings()
            verify(simWidgetVisibilityRepository, times(2)).getSimSettings(any())
        }

    @Test
    fun `save should update general and sim specific settings`() =
        runTest {
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
            whenever(simIdentityRepository.getIdentity(0)).thenReturn("sim0")
            whenever(simIdentityRepository.getIdentity(1)).thenReturn("sim1")

            repository.save(state)

            verify(settingsRepository).updateAppSettings(any())
            verify(simWidgetVisibilityRepository).setSimSettingsBatch(any())
        }
}
