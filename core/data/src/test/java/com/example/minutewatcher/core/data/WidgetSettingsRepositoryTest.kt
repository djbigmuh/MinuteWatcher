package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimWidgetVisibilityRepository
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.IdentitySource
import com.example.minutewatcher.core.model.SimIdentity
import com.example.minutewatcher.core.model.SimSettingsState
import com.example.minutewatcher.core.model.WidgetSettingsState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WidgetSettingsRepositoryTest {
    private val appSettingsRepository: AppSettingsRepository = mock()
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simWidgetVisibilityRepository: SimWidgetVisibilityRepository = mock()

    private lateinit var repository: WidgetSettingsRepository

    @Before
    fun setup() {
        initTestLogger()
        repository =
            WidgetSettingsRepository(
                appSettingsRepository = appSettingsRepository,
                simIdentityRepository = simIdentityRepository,
                simWidgetVisibilityRepository = simWidgetVisibilityRepository,
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
                    widgetLimitExceeded = false,
                )
            whenever(appSettingsRepository.getAppSettings()).thenReturn(flowOf(appSettings))

            val result = repository.load()

            assertNotNull(result)
            assertFalse(result.settingsLoadFailed)
            assertFalse(result.simListLoadFailed)
            assertEquals(1, result.layoutMode)
            assertEquals(3, result.simStates.size)
        }

    @Test
    fun `load sets simListLoadFailed when active sims flow is empty`() =
        runTest {
            // firstWithTimeout on flowOf(emptyList()) returns emptyList, not null.
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(emptyList()))

            val appSettings = AppSettings()
            whenever(appSettingsRepository.getAppSettings()).thenReturn(flowOf(appSettings))

            val result = repository.load()

            assertNotNull(result)
            assertFalse(result.simListLoadFailed) // Empty list is a valid result (0 SIMs)
            assertEquals(0, result.simStates.size)
        }

    @Test
    fun `load correctly detects sim list updates via flow`() =
        runTest {
            val activeSimsFlow = MutableSharedFlow<List<ActiveSimInfo>>(replay = 1)
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(activeSimsFlow)

            val appSettings = AppSettings()
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
    fun `saveGeneralSettings updates underlying app settings repository`() =
        runTest {
            val state =
                WidgetSettingsState(
                    layoutMode = 2,
                    simArrangement = 1,
                    backgroundColor = 0xFF111111.toInt(),
                    transparency = 50,
                )

            repository.saveGeneralSettings(state)

            verify(appSettingsRepository).updateAppSettings(any())
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

    private fun createMockActiveSims(): List<ActiveSimInfo> =
        listOf(
            createMockSim(0, "id0", true),
            createMockSim(1, "id1", true),
            createMockSim(2, "id2", false),
        )

    private fun createMockSim(
        slot: Int,
        id: String,
        isStable: Boolean,
    ): ActiveSimInfo {
        val identity =
            SimIdentity(
                stableId = id,
                subscriptionId = if (isStable) 100 + slot else null,
                slotIndex = slot,
                source = if (isStable) IdentitySource.ICCID else IdentitySource.SLOT_FALLBACK,
                isStable = isStable,
            )
        return ActiveSimInfo(
            identity = identity,
            slotIndex = slot,
            subscriptionId = identity.subscriptionId ?: (100 + slot),
            iccId = if (isStable) id else null,
            displayName = "SIM $slot",
            carrierName = "Carrier",
            mccMnc = "12345",
            isDefaultData = (slot == 0),
            isDefaultVoice = (slot == 0),
        )
    }
}
