package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimWidgetVisibilityRepository
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.AppTheme
import com.example.minutewatcher.core.model.SimIdentity
import com.example.minutewatcher.core.model.SimSettingsState
import com.example.minutewatcher.core.model.WidgetSettingsState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetSettingsRepositoryTest {
    private lateinit var repository: WidgetSettingsRepository
    private val settingsRepository: AppSettingsRepository = mock()
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simWidgetVisibilityRepository: SimWidgetVisibilityRepository = mock()

    @Before
    fun setup() {
        initTestLogger()
        repository =
            WidgetSettingsRepository(
                settingsRepository,
                simIdentityRepository,
                simWidgetVisibilityRepository,
            )
    }

    @Test
    fun `load should return state populated from app settings`() =
        runTest {
            val appSettings = createAppSettings()
            val appSettingsFlow = flowOf(appSettings)
            whenever(settingsRepository.getAppSettings()).thenReturn(appSettingsFlow)

            val activeSims = createMockActiveSims()
            val activeSimsFlow = flowOf(activeSims)
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(activeSimsFlow)

            val simSettings = createMockSimSettings()
            val batchSettings = mapOf("id0" to simSettings, "id1" to simSettings, "id2" to simSettings)
            val batchSettingsFlow = flowOf(batchSettings)
            whenever(simWidgetVisibilityRepository.getSimSettingsBatch(any())).thenReturn(batchSettingsFlow)

            val result = repository.load()

            assertEquals(1, result.layoutMode)
            assertEquals(1, result.simArrangement)
            assertEquals(80, result.transparency)
            assertEquals(3, result.fontSize)
            assertEquals(0xFF0000FF.toInt(), result.backgroundColor)
            assertEquals(true, result.notifyLowMinutes)
            assertEquals(false, result.notifyLowTraffic)

            assertEquals(3, result.simStates.size)

            @Suppress("UnusedFlow")
            verify(settingsRepository).getAppSettings()
            @Suppress("UnusedFlow")
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
            // Моки нужны для стабов getIdentity; результат вызова (String) не потребляется напрямую,
            // но используется внутри Mockito для регистрации стаба.
            @Suppress("UNUSED_EXPRESSION")
            whenever(simIdentityRepository.getIdentity(0)).thenReturn("sim0")
            @Suppress("UNUSED_EXPRESSION")
            whenever(simIdentityRepository.getIdentity(1)).thenReturn("sim1")

            repository.save(state)

            verify(settingsRepository).updateAppSettings(any())
            verify(simWidgetVisibilityRepository).setSimSettingsBatch(
                org.mockito.kotlin.check { map ->
                    assertEquals(4, map.size)
                    assertEquals("sim0", map.keys.first { it == "sim0" })
                    assertEquals("sim_0", map.keys.first { it == "sim_0" })
                    assertEquals("sim1", map.keys.first { it == "sim1" })
                    assertEquals("sim_1", map.keys.first { it == "sim_1" })
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
            @Suppress("UNUSED_EXPRESSION")
            whenever(simIdentityRepository.getIdentity(2)).thenReturn("sim2_inactive")

            repository.save(state)

            verify(simWidgetVisibilityRepository).setSimSettingsBatch(
                org.mockito.kotlin.check { map ->
                    assertEquals(2, map.size)
                    assertEquals(true, map["sim2_inactive"]?.showSim)
                    assertEquals(true, map["sim_2"]?.showSim)
                },
            )
        }

    @Test
    fun `saveSimSettings should return list of failed slots on exception`() =
        runTest {
            val state =
                WidgetSettingsState(
                    simStates =
                        mapOf(
                            0 to SimSettingsState(showSim = true),
                            1 to SimSettingsState(showSim = true),
                        ),
                )
            whenever(simIdentityRepository.getIdentity(0))
                .thenThrow(
                    com.example.minutewatcher.core.domain
                        .SeedUnavailableException(0),
                )
            whenever(simIdentityRepository.getIdentity(1)).thenReturn("sim1")

            val failedSlots = repository.saveSimSettings(state)

            assertEquals(listOf(0), failedSlots)
            verify(simWidgetVisibilityRepository).setSimSettingsBatch(
                org.mockito.kotlin.check { map ->
                    assertEquals(2, map.size)
                    assertEquals(true, map["sim1"]?.showSim)
                    assertEquals(true, map["sim_1"]?.showSim)
                },
            )
        }

    @Test
    @Suppress("UNUSED_VARIABLE")
    fun `load returns fallback with simListLoadFailed when observeActiveSims times out`() =
        runTest {
            val appSettingsFlow = flowOf(AppSettings())
            whenever(settingsRepository.getAppSettings()).thenReturn(appSettingsFlow)

            // observeActiveSims never emits
            val activeSimsFlow = MutableSharedFlow<List<ActiveSimInfo>>()
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(activeSimsFlow)

            val batchSettingsFlow = flowOf(emptyMap<String, SimSettingsState>())
            whenever(simWidgetVisibilityRepository.getSimSettingsBatch(any())).thenReturn(batchSettingsFlow)

            val result = repository.load()

            assertTrue("simStates should be empty on load failure", result.simStates.isEmpty())
            assertTrue("General settings should be loaded successfully", !result.settingsLoadFailed)
            assertTrue("SIM list should be marked as failed", result.simListLoadFailed)

            // getSimSettingsBatch не должен вызываться при таймауте observeActiveSims,
            // так как список SIM не был получен и загружать настройки не для чего.
            @Suppress("UnusedFlow")
            verify(simWidgetVisibilityRepository, never()).getSimSettingsBatch(any())
        }

    @Test
    fun `save should be sequential and protected by mutex`() =
        runTest {
            val state = WidgetSettingsState(simStates = mapOf(0 to SimSettingsState()))
            whenever(simIdentityRepository.getIdentity(0)).thenReturn("sim0")

            val isRunning = AtomicBoolean(false)
            val overlapDetected = AtomicBoolean(false)

            whenever(simWidgetVisibilityRepository.setSimSettingsBatch(any())).doAnswer {
                if (isRunning.getAndSet(true)) {
                    overlapDetected.set(true)
                }
                // Simulate some work
                Thread.sleep(20)
                isRunning.set(false)
            }

            // Launch multiple concurrent saves
            val jobs =
                List(5) {
                    launch {
                        repository.save(state)
                    }
                }
            jobs.joinAll()

            assertFalse("Concurrent save operations detected", overlapDetected.get())
        }

    @Test
    fun `save should skip general settings if sim settings fail with exception`() =
        runTest {
            val state = WidgetSettingsState(simStates = mapOf(0 to SimSettingsState()))
            // saveSimSettings will fail because setSimSettingsBatch throws
            whenever(simIdentityRepository.getIdentity(0)).thenReturn("sim0")
            whenever(simWidgetVisibilityRepository.setSimSettingsBatch(any()))
                .thenThrow(RuntimeException("DataStore error"))

            repository.save(state)

            // General settings should NOT be updated because saveSimSettings failed first
            verify(settingsRepository, never()).updateAppSettings(any())
        }

    private fun createAppSettings() =
        AppSettings(
            theme = AppTheme.LIGHT,
            language = "en",
            notifyLowMinutes = true,
            notifyLowTraffic = false,
            widgetLayoutMode = 1,
            widgetSimArrangement = 1,
            widgetTransparency = 80,
            widgetFontSize = 3,
            widgetBackgroundColor = 0xFF0000FF.toInt(),
            migrationV1Completed = true,
        )

    private fun createMockActiveSims(): List<ActiveSimInfo> {
        val identity0 =
            SimIdentity(
                "id0",
                100,
                0,
                com.example.minutewatcher.core.model.IdentitySource.ICCID,
            )
        val identity1 =
            SimIdentity(
                "id1",
                101,
                1,
                com.example.minutewatcher.core.model.IdentitySource.ICCID,
            )
        val identity2 =
            SimIdentity(
                "id2",
                null,
                2,
                com.example.minutewatcher.core.model.IdentitySource.SLOT_FALLBACK,
            )

        return listOf(
            ActiveSimInfo(
                identity = identity0,
                slotIndex = 0,
                subscriptionId = 100,
                displayName = "MTS",
                carrierName = "MTS",
                mccMnc = "25001",
                isDefaultData = true,
                isDefaultVoice = true,
            ),
            ActiveSimInfo(
                identity = identity1,
                slotIndex = 1,
                subscriptionId = 101,
                displayName = "MegaFon",
                carrierName = "MegaFon",
                mccMnc = "25002",
                isDefaultData = false,
                isDefaultVoice = false,
            ),
            ActiveSimInfo(
                identity = identity2,
                slotIndex = 2,
                subscriptionId = 102,
                displayName = "Sim 2",
                carrierName = "Carrier 2",
                mccMnc = "25003",
                isDefaultData = false,
                isDefaultVoice = false,
            ),
        )
    }

    private fun createMockSimSettings() =
        SimSettingsState(
            showSim = true,
            showIcon = true,
            showName = true,
            showMinutes = true,
            showMinutesLimit = false,
            showTraffic = true,
            showTrafficLimit = false,
        )
}
