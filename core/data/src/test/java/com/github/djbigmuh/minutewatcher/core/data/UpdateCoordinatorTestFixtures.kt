package com.github.djbigmuh.minutewatcher.core.data

import com.github.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import com.github.djbigmuh.minutewatcher.core.common.util.initTestLogger
import com.github.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import com.github.djbigmuh.minutewatcher.core.domain.DataRefreshNotifier
import com.github.djbigmuh.minutewatcher.core.domain.SimWidgetVisibilityRepository
import com.github.djbigmuh.minutewatcher.core.domain.UsageStateRepository
import com.github.djbigmuh.minutewatcher.core.domain.WidgetUpdateResult
import com.github.djbigmuh.minutewatcher.core.domain.WidgetUpdater
import com.github.djbigmuh.minutewatcher.core.model.AppSettings
import com.github.djbigmuh.minutewatcher.core.model.DataSourceType
import com.github.djbigmuh.minutewatcher.core.model.GlobalSnapshot
import com.github.djbigmuh.minutewatcher.core.model.MeasurementUnit
import com.github.djbigmuh.minutewatcher.core.model.PackageType
import com.github.djbigmuh.minutewatcher.core.model.SchemaVersion
import com.github.djbigmuh.minutewatcher.core.model.SemanticEntity
import com.github.djbigmuh.minutewatcher.core.model.SimCardUiModel
import com.github.djbigmuh.minutewatcher.core.model.SimSettingsState
import com.github.djbigmuh.minutewatcher.core.model.SimSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class FakeAppSettingsRepositoryForCoordinator(
    initialSettings: AppSettings = AppSettings(),
) : AppSettingsRepository {
    val settingsFlow = MutableStateFlow(initialSettings)

    override fun observeAppSettings(): StateFlow<AppSettings> = settingsFlow

    override fun getAppSettings(): Flow<AppSettings> = settingsFlow

    override suspend fun updateAppSettings(transform: (AppSettings) -> AppSettings) {
        settingsFlow.value = transform(settingsFlow.value)
    }
}

class FakeSimWidgetVisibilityRepositoryForCoordinator : SimWidgetVisibilityRepository {
    val simSettingsFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 10)

    override fun observeSimSettings(): Flow<Unit> = simSettingsFlow

    override fun getSimSettingsBatch(identities: List<String>): Flow<Map<String, SimSettingsState>> =
        MutableStateFlow(emptyMap())

    override suspend fun setSimSettingsBatch(settingsMap: Map<String, SimSettingsState>) = Unit
}

internal fun createTestSimCardUiModel(slot: Int = 0) =
    SimCardUiModel(
        slot = slot,
        identity = "sim_$slot",
        operatorName = "Operator",
        iconRes = 0,
        minutesUsed = 0,
        minutesLimit = 100,
        manualAdjustment = 0,
        minutesRemaining = 100,
        effectiveMinutesLimit = 100,
        isMinutesLimitSet = true,
        trafficUsedMb = 0f,
        trafficLimitMb = 1000,
        trafficRemainingGb = 1f,
        effectiveTrafficLimitGb = 1f,
        trafficAdjustmentGb = 0f,
        isTrafficLimitSet = true,
        autoResetEnabled = true,
        autoResetDay = 1,
        rolloverEnabled = false,
    )

internal fun createEmptySnapshot() =
    GlobalSnapshot(
        version = SchemaVersion(1, 0, 0),
        timestamp = 0L,
        simSnapshots = emptyList(),
        capabilities = emptyList(),
    )

internal fun createSnapshot(
    timestamp: Long,
    content: String,
    suppressUpdate: Boolean = false,
    isManual: Boolean = false,
) = GlobalSnapshot(
    version = SchemaVersion(1, 0, content.hashCode()),
    timestamp = timestamp,
    isManualUpdate = isManual,
    suppressWidgetUpdate = suppressUpdate,
    simSnapshots =
        listOf(
            SimSnapshot(
                slot = 0,
                identity = "sim_0",
                operatorName = "MTS",
                autoResetDay = 1,
                entities =
                    mapOf(
                        PackageType.MINUTES to
                            SemanticEntity(
                                id = content,
                                simSlot = 0,
                                operatorName = "MTS",
                                packageType = PackageType.MINUTES,
                                limit = 100L,
                                used = 10L,
                                remaining = 90L,
                                unit = MeasurementUnit.MINUTES,
                                periodStart = 0L,
                                periodEnd = 0L,
                                sourceType = DataSourceType.SYSTEM,
                                lastUpdateTime = 0L,
                            ),
                    ),
            ),
        ),
    capabilities = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
abstract class UpdateCoordinatorTestFixtures {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    protected val dataRefreshNotifier: DataRefreshNotifier = mock()
    protected val widgetUpdater: WidgetUpdater = mock()
    protected val usageStateRepository: UsageStateRepository = mock()
    protected val appSettingsRepository = FakeAppSettingsRepositoryForCoordinator()
    protected val simWidgetVisibilityRepository = FakeSimWidgetVisibilityRepositoryForCoordinator()

    protected val snapshotFlow = MutableStateFlow<GlobalSnapshot>(createEmptySnapshot())
    protected val simFlow = MutableStateFlow<List<SimCardUiModel>>(listOf(createTestSimCardUiModel()))

    @Before
    open fun setup() {
        initTestLogger()
        snapshotFlow.value = createEmptySnapshot()
        runBlocking {
            whenever(widgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Success())
        }
        whenever(usageStateRepository.observeSimCardUiModels()).thenReturn(simFlow)
        whenever(usageStateRepository.observeSnapshot()).thenReturn(snapshotFlow)
    }

    protected fun createCoordinator(
        scope: CoroutineScope,
        customAppSettings: AppSettingsRepository = appSettingsRepository,
        customSimSettings: SimWidgetVisibilityRepository = simWidgetVisibilityRepository,
        customWidgetUpdater: WidgetUpdater = widgetUpdater,
    ): UpdateCoordinator =
        UpdateCoordinator(
            dataRefreshNotifier = dataRefreshNotifier,
            widgetUpdater = customWidgetUpdater,
            usageStateRepository = usageStateRepository,
            appSettingsRepository = customAppSettings,
            simWidgetVisibilityRepository = customSimSettings,
            scope = scope,
        )
}
