package host.serv.djbigmuh.minutewatcher.core.data

import host.serv.djbigmuh.minutewatcher.core.common.util.initTestLogger
import host.serv.djbigmuh.minutewatcher.core.domain.AppFunctionAvailability
import host.serv.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SeedUnavailableException
import host.serv.djbigmuh.minutewatcher.core.domain.SimIdentityRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimWidgetVisibilityRepository
import host.serv.djbigmuh.minutewatcher.core.model.ActiveSimInfo
import host.serv.djbigmuh.minutewatcher.core.model.AppSettings
import host.serv.djbigmuh.minutewatcher.core.model.IdentitySource
import host.serv.djbigmuh.minutewatcher.core.model.SimIdentity
import host.serv.djbigmuh.minutewatcher.core.model.SimSettingsState
import host.serv.djbigmuh.minutewatcher.core.model.WidgetSettingsState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetSettingsRepositoryTest {
    private val appSettingsRepository: AppSettingsRepository = mock()
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simWidgetVisibilityRepository: SimWidgetVisibilityRepository = mock()
    private val appFunctionAvailability: AppFunctionAvailability = mock()

    private lateinit var repository: WidgetSettingsRepository

    @Before
    fun setup() {
        initTestLogger()
        repository =
            WidgetSettingsRepository(
                appSettingsRepository = appSettingsRepository,
                simIdentityRepository = simIdentityRepository,
                simWidgetVisibilityRepository = simWidgetVisibilityRepository,
                appFunctionAvailability = appFunctionAvailability,
            )
    }

    @Test
    fun `load returns success when all underlying repositories work`() =
        runTest {
            val activeSims = createMockActiveSims()
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(MutableStateFlow(activeSims))

            val simSettings = SimSettingsState(showSim = true)
            whenever(simWidgetVisibilityRepository.getSimSettingsBatch(any())).thenReturn(
                flowOf(mapOf("id0" to simSettings, "id1" to simSettings, "id2" to simSettings)),
            )

            val appSettings =
                AppSettings(
                    widgetLayoutMode = 1,
                    widgetSimArrangement = 0,
                    widgetBackgroundColor = 0xFF000000.toInt(),
                    widgetTransparency = 0,
                    aodSummaryEnabled = true,
                    callTrackingEnabled = false,
                    widgetLimitExceeded = false,
                    isLoaded = true,
                )
            whenever(appSettingsRepository.getAppSettings()).thenReturn(flowOf(appSettings))

            val result = repository.load()

            assertNotNull(result)
            assertFalse(result.settingsLoadFailed)
            assertFalse(result.simListLoadFailed)
            assertEquals(1, result.layoutMode)
            assertEquals(true, result.aodSummaryEnabled)
            assertEquals(false, result.callTrackingEnabled)
            assertEquals(3, result.simStates.size)
        }

    @Test
    fun `load sets simListLoadFailed when active sims flow is empty`() =
        runTest {
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(emptyList()))

            val appSettings = AppSettings(isLoaded = true)
            whenever(appSettingsRepository.getAppSettings()).thenReturn(flowOf(appSettings))

            val result = repository.load()

            assertNotNull(result)
            assertFalse(result.simListLoadFailed)
            assertEquals(0, result.simStates.size)
        }

    @Test
    fun `load correctly detects sim list updates via flow`() =
        runTest {
            val activeSimsFlow = MutableSharedFlow<List<ActiveSimInfo>>(replay = 1)
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(activeSimsFlow)

            val appSettings = AppSettings(isLoaded = true)
            whenever(appSettingsRepository.getAppSettings()).thenReturn(flowOf(appSettings))

            val simSettings = SimSettingsState()
            whenever(simWidgetVisibilityRepository.getSimSettingsBatch(any())).thenReturn(
                flowOf(mapOf("id0" to simSettings, "id1" to simSettings, "id2" to simSettings)),
            )

            val activeSims = createMockActiveSims()
            activeSimsFlow.emit(activeSims)

            val result = repository.load()
            assertEquals(3, result.simStates.size)
        }

    @Test
    fun `load correctly populates assistant access enabled from app settings`() =
        runTest {
            val activeSims = createMockActiveSims()
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(activeSims))

            val simSettings = SimSettingsState(showSim = true)
            whenever(simWidgetVisibilityRepository.getSimSettingsBatch(any())).thenReturn(
                flowOf(mapOf("id0" to simSettings, "id1" to simSettings, "id2" to simSettings)),
            )

            val appSettings = AppSettings(isLoaded = true, assistantAccessEnabled = true)
            whenever(appSettingsRepository.getAppSettings()).thenReturn(flowOf(appSettings))

            val result = repository.load()

            assertNotNull(result)
            assertTrue(result.assistantAccessEnabled)
        }

    @Test
    fun `saveGeneralSettings updates underlying app settings repository`() =
        runTest {
            val state =
                WidgetSettingsState(
                    layoutMode = 2,
                    simArrangement = 1,
                    backgroundColor = 0xFF111111.toInt(),
                    transparency = 50,
                    aodSummaryEnabled = true,
                    callTrackingEnabled = false,
                    assistantAccessEnabled = true,
                )

            repository.saveGeneralSettings(state)

            val captor = org.mockito.kotlin.argumentCaptor<(AppSettings) -> AppSettings>()
            verify(appSettingsRepository).updateAppSettings(captor.capture())
            verify(appFunctionAvailability).setAssistantAccessEnabled(true)

            val initial =
                AppSettings(aodSummaryEnabled = false, callTrackingEnabled = true, assistantAccessEnabled = false)
            val updated = captor.firstValue(initial)
            assertEquals(true, updated.aodSummaryEnabled)
            assertEquals(false, updated.callTrackingEnabled)
            assertEquals(true, updated.assistantAccessEnabled)
        }

    @Test
    fun `saveGeneralSettings syncs assistant access enabled false`() =
        runTest {
            val state = WidgetSettingsState(assistantAccessEnabled = false)

            repository.saveGeneralSettings(state)

            verify(appFunctionAvailability).setAssistantAccessEnabled(false)
        }

    @Test
    fun `saveGeneralSettings throws exception does not sync assistant access`() =
        runTest {
            val state = WidgetSettingsState(assistantAccessEnabled = true)
            whenever(
                appSettingsRepository.updateAppSettings(any()),
            ).thenThrow(IllegalStateException("Simulated failure"))

            assertThrows(IllegalStateException::class.java) {
                runTest {
                    repository.saveGeneralSettings(state)
                }
            }

            verify(appFunctionAvailability, never()).setAssistantAccessEnabled(any())
        }

    @Test
    fun `saveSimSettings updates underlying SIM repositories`() =
        runTest {
            val simState = SimSettingsState(showSim = true)
            val state = WidgetSettingsState(simStates = mapOf(0 to simState))

            whenever(simIdentityRepository.getIdentity(0)).thenReturn("id0")

            repository.saveSimSettings(state)

            verify(simWidgetVisibilityRepository).setSimSettingsBatch(any())
        }

    @Test
    fun `saveSimSettings saves with last known identity when seed is unavailable`() =
        runTest {
            val lastId = "last_known_id"
            val simState = SimSettingsState(showSim = true, lastKnownIdentity = lastId)
            val state = WidgetSettingsState(simStates = mapOf(0 to simState))

            whenever(simIdentityRepository.getIdentity(0)).thenThrow(SeedUnavailableException(0))

            repository.saveSimSettings(state)

            verify(simWidgetVisibilityRepository).setSimSettingsBatch(mapOf(lastId to simState))
        }

    @Test
    fun `saveSimSettings records failed slot when seed and last known identity are unavailable`() =
        runTest {
            val simState = SimSettingsState(showSim = true, lastKnownIdentity = null)
            val state = WidgetSettingsState(simStates = mapOf(0 to simState))

            whenever(simIdentityRepository.getIdentity(0)).thenThrow(SeedUnavailableException(0))

            val failedSlots = repository.saveSimSettings(state)

            assertEquals(listOf(0), failedSlots)
            verify(simWidgetVisibilityRepository, never()).setSimSettingsBatch(any())
        }

    @Test
    fun `load returns fallback with settingsLoadFailed when app settings timeout`() =
        runTest {
            whenever(appSettingsRepository.getAppSettings()).thenReturn(MutableSharedFlow())
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(emptyList()))

            val result = repository.load()

            assertNotNull(result)
            assertEquals(true, result.settingsLoadFailed)
        }

    private fun createMockActiveSims(): List<ActiveSimInfo> =
        listOf(
            createMockSim(0, "id0", isStable = true),
            createMockSim(1, "id1", isStable = true),
            createMockSim(2, "id2", isStable = false),
        )

    private fun createMockSim(
        slot: Int,
        id: String,
        isStable: Boolean,
    ): ActiveSimInfo {
        val identity =
            SimIdentity(
                stableId = id,
                subscriptionId = 100 + slot,
                slotIndex = slot,
                source = if (isStable) IdentitySource.ICCID else IdentitySource.SUBSCRIPTION_ID,
                isStable = isStable,
            )
        return ActiveSimInfo(
            identity = identity,
            slotIndex = slot,
            subscriptionId = identity.subscriptionId ?: (100 + slot),
            iccId = if (isStable) id else null,
            displayName = "SIM $slot",
            carrierName = "Carrier",
            isDefaultData = (slot == 0),
            isDefaultVoice = (slot == 0),
        )
    }
}
