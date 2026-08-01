package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.DataChangeNotifier
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
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class WidgetSettingsRepositoryTest {
    private lateinit var repository: WidgetSettingsRepository
    private val settingsRepository: AppSettingsRepository = mock()
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simWidgetVisibilityRepository: SimWidgetVisibilityRepository = mock()
    private val dataChangeNotifier: DataChangeNotifier = mock()

    @Before
    fun setup() {
        repository =
            WidgetSettingsRepository(
                settingsRepository,
                simIdentityRepository,
                simWidgetVisibilityRepository,
                dataChangeNotifier,
            )
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
            whenever(settingsRepository.getAppSettings()).thenReturn(flowOf(appSettings))

            val identity0 =
                com.example.minutewatcher.core.model.SimIdentity(
                    "id0",
                    100,
                    0,
                    com.example.minutewatcher.core.model.IdentitySource.ICCID,
                )
            val identity1 =
                com.example.minutewatcher.core.model.SimIdentity(
                    "id1",
                    101,
                    1,
                    com.example.minutewatcher.core.model.IdentitySource.ICCID,
                )
            val identity2 =
                com.example.minutewatcher.core.model.SimIdentity(
                    "id2",
                    null,
                    2,
                    com.example.minutewatcher.core.model.IdentitySource.SLOT_FALLBACK,
                )

            whenever(simIdentityRepository.getSimIdentity(0)).thenReturn(identity0)
            whenever(simIdentityRepository.getSimIdentity(1)).thenReturn(identity1)
            whenever(simIdentityRepository.getSimIdentity(2)).thenReturn(identity2)

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
            val batchSettings = mapOf("id0" to simSettings, "id1" to simSettings, "id2" to simSettings)
            whenever(simWidgetVisibilityRepository.getSimSettingsBatch(any())).thenReturn(flowOf(batchSettings))

            val result = repository.load()

            assertEquals(1, result.layoutMode)
            assertEquals(1, result.simArrangement)
            assertEquals(80, result.transparency)
            assertEquals(3, result.fontSize)
            assertEquals(0xFF0000FF.toInt(), result.backgroundColor)
            assertEquals(true, result.notifyLowMinutes)
            assertEquals(false, result.notifyLowTraffic)

            assertEquals(3, result.simStates.size)

            verify(settingsRepository).getAppSettings()
            verify(simWidgetVisibilityRepository).getSimSettingsBatch(any())
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
                        mapOf(
                            0 to
                                SimSettingsState(
                                    showSim = true,
                                    showIcon = false,
                                    showName = true,
                                    showMinutes = true,
                                    showMinutesLimit = false,
                                    showTraffic = true,
                                    showTrafficLimit = false,
                                ),
                            1 to
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
            verify(simWidgetVisibilityRepository).setSimSettingsBatch(
                org.mockito.kotlin.check { map ->
                    assertEquals(2, map.size)
                    assertEquals("sim0", map.keys.first { map[it] == state.simStates[0] })
                    assertEquals("sim1", map.keys.first { map[it] == state.simStates[1] })
                },
            )
        }

    @Test
    fun `save should include inactive sim settings`() =
        runTest {
            val state =
                WidgetSettingsState(
                    simStates =
                        mapOf(
                            2 to SimSettingsState(showSim = true),
                        ),
                )
            whenever(simIdentityRepository.getIdentity(2)).thenReturn("sim2_inactive")

            repository.save(state)

            verify(simWidgetVisibilityRepository).setSimSettingsBatch(
                org.mockito.kotlin.check { map ->
                    assertEquals(1, map.size)
                    assertEquals(true, map["sim2_inactive"]?.showSim)
                },
            )
        }
}
