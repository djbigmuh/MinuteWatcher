package host.serv.djbigmuh.minutewatcher.core.data.testing

import host.serv.djbigmuh.minutewatcher.core.data.MockSimProvider
import host.serv.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import host.serv.djbigmuh.minutewatcher.core.domain.CallSessionRepository
import host.serv.djbigmuh.minutewatcher.core.domain.DataChangeNotifier
import host.serv.djbigmuh.minutewatcher.core.domain.DataRefreshNotifier
import host.serv.djbigmuh.minutewatcher.core.domain.MigrationFlagsRepository
import host.serv.djbigmuh.minutewatcher.core.domain.RefreshResult
import host.serv.djbigmuh.minutewatcher.core.domain.SeedResolutionResult
import host.serv.djbigmuh.minutewatcher.core.domain.SimIdentityRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimIdentitySeedRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimLimitsRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimNotificationFlagsRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimUsageRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimUsageResetRepository
import host.serv.djbigmuh.minutewatcher.core.domain.UsageStateRepository
import host.serv.djbigmuh.minutewatcher.core.model.ActiveSimInfo
import host.serv.djbigmuh.minutewatcher.core.model.AppSettings
import host.serv.djbigmuh.minutewatcher.core.model.GlobalSnapshot
import host.serv.djbigmuh.minutewatcher.core.model.IdentitySource
import host.serv.djbigmuh.minutewatcher.core.model.MigrationSkipReason
import host.serv.djbigmuh.minutewatcher.core.model.SchemaVersion
import host.serv.djbigmuh.minutewatcher.core.model.SimCardUiModel
import host.serv.djbigmuh.minutewatcher.core.model.SimIdentity
import host.serv.djbigmuh.minutewatcher.core.model.SimUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

class FakeMockSimProvider : MockSimProvider {
    override fun getMockSims(count: Int): List<ActiveSimInfo>? = null
}

class FakeDataChangeNotifier : DataChangeNotifier {
    override suspend fun notifyDataChanged(
        updateWidget: Boolean,
        force: Boolean,
    ): Boolean = true
}

class FakeDataRefreshNotifier : DataRefreshNotifier {
    private val _refreshFlow = MutableSharedFlow<DataRefreshNotifier.RefreshEvent>(replay = 1)
    override val refreshFlow: Flow<DataRefreshNotifier.RefreshEvent> = _refreshFlow.asSharedFlow()

    override suspend fun notifyRefresh(
        isManual: Boolean,
        suppressWidgetUpdate: Boolean,
    ) {
        _refreshFlow.emit(DataRefreshNotifier.RefreshEvent(isManual, suppressWidgetUpdate))
    }
}

class FakeAppSettingsRepository(
    initialSettings: AppSettings = AppSettings(),
) : AppSettingsRepository {
    private val appSettingsFlow = MutableStateFlow(initialSettings)

    override fun getAppSettings(): Flow<AppSettings> = appSettingsFlow.asStateFlow()

    override fun observeAppSettings(): StateFlow<AppSettings> = appSettingsFlow.asStateFlow()

    override suspend fun updateAppSettings(transform: (AppSettings) -> AppSettings) {
        appSettingsFlow.value = transform(appSettingsFlow.value)
    }
}

class FakeSimIdentitySeedRepository(
    private val seedFlowVal: Flow<String> = flowOf("test_seed"),
) : SimIdentitySeedRepository {
    override val seedFlow: Flow<String> = seedFlowVal
    override val isEphemeralSeedActive: StateFlow<Boolean> = MutableStateFlow(false)

    override suspend fun setSeed(seed: String) {}

    override suspend fun getOrCreateSeedOrEphemeralFallback(generator: () -> String): SeedResolutionResult =
        SeedResolutionResult.Persistent("test_seed")

    override suspend fun getOrCreateEphemeralSeed(generator: () -> String): String = "test_seed"
}

class FakeSimIdentityRepository : SimIdentityRepository {
    private val activeSimsFlow = MutableStateFlow<List<ActiveSimInfo>>(emptyList())

    override suspend fun getIdentity(slot: Int): String = "sim_$slot"

    override suspend fun getSimIdentity(slot: Int): SimIdentity =
        SimIdentity(
            stableId = "sim_$slot",
            subscriptionId = slot,
            slotIndex = slot,
            source = IdentitySource.ICCID,
            isStable = true,
        )

    override fun getActiveIdentities(): Flow<List<String>> = flowOf(emptyList())

    override fun observeActiveSims(): Flow<List<ActiveSimInfo>> = activeSimsFlow.asStateFlow()

    override fun observeResolvedActiveSims(): StateFlow<List<ActiveSimInfo>?> = MutableStateFlow(emptyList())

    override suspend fun setSimOperator(
        identity: String,
        name: String,
        brandKey: String?,
    ) {}

    fun setActiveSims(sims: List<ActiveSimInfo>) {
        activeSimsFlow.value = sims
    }
}

class FakeSimUsageRepository : SimUsageRepository {
    override fun getSimUsage(identity: String): Flow<SimUsage> =
        flowOf(
            SimUsage(
                identity = identity,
                slotIndex = 0,
                subscriptionId = 1,
                operator = "MTS",
                minutesUsed = 0,
                minutesLimit = 100,
                trafficUsedMb = 0f,
                trafficLimitMb = 1000,
                manualAdjustment = 0,
                trafficAdjustmentGb = 0f,
                autoReset = false,
                autoResetDay = 1,
                lastResetDate = 0L,
            ),
        )

    override suspend fun incrementMinutesUsedForCall(
        identity: String,
        deltaMinutes: Int,
        callSignature: String,
    ): Boolean = true
}

class FakeCallSessionRepository : CallSessionRepository {
    override fun getLastDataSubId(): Flow<Int> = flowOf(-1)

    override suspend fun setLastDataSubId(subId: Int) {}

    override suspend fun saveCallSession(
        subId: Int,
        startTime: Long,
        elapsedRealtime: Long,
        uptime: Long,
        identity: String?,
    ) {
    }

    override suspend fun clearCallSession(subId: Int) {}

    override fun getCallStartTime(subId: Int): Flow<Long> = flowOf(0L)

    override fun getElapsedRealtime(subId: Int): Flow<Long> = flowOf(0L)

    override fun getUptime(subId: Int): Flow<Long> = flowOf(0L)

    override fun getCallIdentity(subId: Int): Flow<String?> = flowOf(null)

    override suspend fun clearAllCallSessions() {}

    override suspend fun getActiveCallSubIds(): List<Int> = emptyList()
}

class FakeUsageStateRepository : UsageStateRepository {
    override fun observeSimCardUiModels(): Flow<List<SimCardUiModel>> = flowOf(emptyList())

    override fun observeSnapshot(): StateFlow<GlobalSnapshot> =
        MutableStateFlow(GlobalSnapshot(SchemaVersion(1, 0, 0), 0L, emptyList(), emptyList()))

    override fun isDataLoaded(): Boolean = true

    override suspend fun refresh(): RefreshResult =
        RefreshResult.Success(GlobalSnapshot(SchemaVersion(1, 0, 0), 0L, emptyList(), emptyList()))
}

class FakeSimUsageResetRepository : SimUsageResetRepository {
    override suspend fun resetSimUsageManual(identity: String) {}

    override suspend fun resetSimUsageIfDue(
        identity: String,
        autoResetDay: Int,
        nowMillis: Long,
    ): Boolean = false

    override fun getLastTotalBytes(identity: String): Flow<Long> = flowOf(0L)

    override suspend fun setLastTotalBytes(
        identity: String,
        bytes: Long,
        expectedLastResetDate: Long,
    ): Boolean = true

    override suspend fun incrementTrafficAndSetBaseline(
        identity: String,
        deltaMb: Float,
        totalBytes: Long,
        expectedLastResetDate: Long,
    ): Boolean = true

    override suspend fun ensureTrackingWindowInitialized(identity: String): Boolean = true

    override fun getBaselineRetryCount(identity: String): Flow<Int?> = flowOf(null)

    override suspend fun setBaselineRetryCount(
        identity: String,
        count: Int?,
    ) {}

    override suspend fun cleanupBaselineRetries(activeIdentities: Set<String>) {}
}

class FakeSimLimitsRepository : SimLimitsRepository {
    override fun getMonthlyLimit(identity: String): Flow<Int> = flowOf(100)

    override fun getTrafficLimit(identity: String): Flow<Int> = flowOf(1000)

    override suspend fun updateSimLimits(
        identity: String,
        minutesLimit: Int,
        trafficLimitMb: Int,
        rolloverEnabled: Boolean,
    ) {
    }

    override fun getAutoReset(identity: String): Flow<Boolean> = flowOf(false)

    override fun getAutoResetDay(identity: String): Flow<Int> = flowOf(1)

    override suspend fun setAutoReset(
        identity: String,
        day: Int,
        enabled: Boolean,
    ) {}
}

class FakeMigrationFlagsRepository : MigrationFlagsRepository {
    override fun isMigrationV1Completed(): Flow<Boolean> = flowOf(true)

    override suspend fun setMigrationV1Completed(completed: Boolean) {}

    override fun isMigrationIdentityStabilityCompleted(): Flow<Boolean> = flowOf(true)

    override suspend fun setMigrationIdentityStabilityCompleted(completed: Boolean) {}

    override suspend fun setMigrationV1Skipped(reason: MigrationSkipReason) {}

    override fun isInitialSetupCompleted(): Flow<Boolean> = flowOf(true)

    override suspend fun setInitialSetupCompleted(completed: Boolean) {}
}

class FakeSimNotificationFlagsRepository : SimNotificationFlagsRepository {
    override fun getNotifiedLowMinutes(identity: String): Flow<Boolean> = flowOf(false)

    override suspend fun setNotifiedLowMinutes(
        identity: String,
        notified: Boolean,
    ) {}

    override fun getNotifiedLowTraffic(identity: String): Flow<Boolean> = flowOf(false)

    override suspend fun setNotifiedLowTraffic(
        identity: String,
        notified: Boolean,
    ) {}

    override fun getNotifiedExhaustedMinutes(identity: String): Flow<Boolean> = flowOf(false)

    override suspend fun setNotifiedExhaustedMinutes(
        identity: String,
        notified: Boolean,
    ) {}

    override fun getNotifiedExhaustedTraffic(identity: String): Flow<Boolean> = flowOf(false)

    override suspend fun setNotifiedExhaustedTraffic(
        identity: String,
        notified: Boolean,
    ) {}

    override fun getNotifiedCriticalMinutes(identity: String): Flow<Boolean> = flowOf(false)

    override suspend fun setNotifiedCriticalMinutes(
        identity: String,
        notified: Boolean,
    ) {}

    override fun getNotifiedCriticalTraffic(identity: String): Flow<Boolean> = flowOf(false)

    override suspend fun setNotifiedCriticalTraffic(
        identity: String,
        notified: Boolean,
    ) {}
}
