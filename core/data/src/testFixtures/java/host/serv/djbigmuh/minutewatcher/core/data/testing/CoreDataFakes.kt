package host.serv.djbigmuh.minutewatcher.core.data.testing

import host.serv.djbigmuh.minutewatcher.core.data.MockSimProvider
import host.serv.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import host.serv.djbigmuh.minutewatcher.core.domain.CallSessionRepository
import host.serv.djbigmuh.minutewatcher.core.domain.DataChangeNotifier
import host.serv.djbigmuh.minutewatcher.core.domain.DataRefreshNotifier
import host.serv.djbigmuh.minutewatcher.core.domain.MigrationFlagsRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimIdentityRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimIdentitySeedRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimLimitsRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimNotificationFlagsRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimUsageRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimUsageResetRepository
import host.serv.djbigmuh.minutewatcher.core.domain.UsageStateRepository
import host.serv.djbigmuh.minutewatcher.core.model.AppSettings
import host.serv.djbigmuh.minutewatcher.core.model.SimInfo
import host.serv.djbigmuh.minutewatcher.core.model.SimLimits
import host.serv.djbigmuh.minutewatcher.core.model.SimUsage
import host.serv.djbigmuh.minutewatcher.core.model.UsageStateSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

class FakeMockSimProvider : MockSimProvider {
    override fun getMockSims(count: Int): List<SimInfo>? = null
}

class FakeDataChangeNotifier : DataChangeNotifier {
    private val _changes = MutableSharedFlow<Unit>(replay = 1)
    override val changes: Flow<Unit> = _changes.asSharedFlow()

    override suspend fun notifyDataChanged() {
        _changes.emit(Unit)
    }
}

class FakeDataRefreshNotifier : DataRefreshNotifier {
    private val _refreshRequests = MutableSharedFlow<Unit>(replay = 1)
    override val refreshRequests: Flow<Unit> = _refreshRequests.asSharedFlow()

    override suspend fun requestRefresh() {
        _refreshRequests.emit(Unit)
    }

    override fun observeRefreshRequests(): Flow<Unit> = _refreshRequests.asSharedFlow()
}

class FakeAppSettingsRepository(
    initialSettings: AppSettings = AppSettings(),
) : AppSettingsRepository {
    private val appSettingsFlow = MutableStateFlow(initialSettings)

    override fun getAppSettings(): Flow<AppSettings> = appSettingsFlow.asStateFlow()

    override fun observeAppSettings(): Flow<AppSettings> = appSettingsFlow.asStateFlow()

    override suspend fun updateAppSettings(transform: (AppSettings) -> AppSettings) {
        appSettingsFlow.value = transform(appSettingsFlow.value)
    }
}

class FakeSimIdentitySeedRepository(
    private val seedFlowVal: Flow<String?> = flowOf("test_seed"),
) : SimIdentitySeedRepository {
    override val seedFlow: Flow<String?> = seedFlowVal

    override suspend fun getOrCreateSeedOrEphemeralFallback(subscriptionId: Int): host.serv.djbigmuh.minutewatcher.core.domain.SeedResolutionResult =
        host.serv.djbigmuh.minutewatcher.core.domain.SeedResolutionResult.Persistent("test_seed")

    override suspend fun getSeed(subscriptionId: Int): String? = "test_seed"

    override suspend fun clearSeed(subscriptionId: Int) {}
}

class FakeSimIdentityRepository : SimIdentityRepository {
    private val activeSimsFlow = MutableStateFlow<List<SimInfo>>(emptyList())

    override fun observeActiveSims(): Flow<List<SimInfo>> = activeSimsFlow.asStateFlow()

    override fun getActiveIdentities(): Flow<List<String>> = flowOf(emptyList())

    override suspend fun getIdentity(subscriptionId: Int): SimInfo? = null

    override suspend fun getIdentityById(identity: String): SimInfo? = null

    override suspend fun setSimOperator(identity: String, operatorName: String) {}

    override suspend fun refreshActiveSims() {}

    fun setActiveSims(sims: List<SimInfo>) {
        activeSimsFlow.value = sims
    }
}

class FakeSimUsageRepository : SimUsageRepository {
    override fun getUsage(identity: String): Flow<SimUsage> = flowOf(SimUsage(identity = identity))

    override fun getAllUsage(): Flow<Map<String, SimUsage>> = flowOf(emptyMap())

    override suspend fun incrementMinutesUsedForCall(identity: String, minutes: Long, timestamp: Long): Boolean = true

    override suspend fun resetUsage(identity: String) {}

    override suspend fun setUsage(identity: String, usage: SimUsage) {}
}

class FakeCallSessionRepository : CallSessionRepository {
    override fun getCallIdentity(subscriptionId: Int): Flow<String?> = flowOf(null)

    override fun getCallStartTime(subscriptionId: Int): Flow<Long> = flowOf(0L)

    override fun getElapsedRealtime(subscriptionId: Int): Flow<Long> = flowOf(0L)

    override fun getUptime(subscriptionId: Int): Flow<Long> = flowOf(0L)

    override fun getActiveCallSubIds(): List<Int> = emptyList()

    override suspend fun setCallSession(subscriptionId: Int, identity: String, startTime: Long, elapsedRealtime: Long, uptime: Long) {}

    override suspend fun clearCallSession(subscriptionId: Int) {}
}

class FakeUsageStateRepository : UsageStateRepository {
    override fun observeUsageState(): Flow<UsageStateSnapshot> = flowOf(UsageStateSnapshot())

    override suspend fun refreshState(): UsageStateRepository.RefreshResult = UsageStateRepository.RefreshResult.Success(UsageStateSnapshot())
}

class FakeSimUsageResetRepository : SimUsageResetRepository {
    override suspend fun ensureTrackingWindowInitialized(identity: String): Boolean = true

    override suspend fun setLastTotalBytes(identity: String, totalRxBytes: Long, totalTxBytes: Long): Boolean = true

    override suspend fun incrementTrafficAndSetBaseline(identity: String, deltaRx: Long, deltaTx: Long, totalRx: Long, totalTx: Long): Boolean = true

    override suspend fun getTrackingWindow(identity: String): host.serv.djbigmuh.minutewatcher.core.model.SimTrackingWindow? = null

    override suspend fun resetTraffic(identity: String) {}
}

class FakeSimLimitsRepository : SimLimitsRepository {
    override fun getLimits(identity: String): Flow<SimLimits> = flowOf(SimLimits())

    override fun getAllLimits(): Flow<Map<String, SimLimits>> = flowOf(emptyMap())

    override suspend fun setLimits(identity: String, limits: SimLimits) {}
}

class FakeMigrationFlagsRepository : MigrationFlagsRepository {
    override fun getMigrationCompleted(): Flow<Boolean> = flowOf(true)

    override suspend fun setMigrationCompleted(completed: Boolean) {}

    override fun getSharedPreferencesMigrationCompleted(): Flow<Boolean> = flowOf(true)

    override suspend fun setSharedPreferencesMigrationCompleted(completed: Boolean) {}
}

class FakeSimNotificationFlagsRepository : SimNotificationFlagsRepository {
    override fun getNotifiedLowMinutes(identity: String): Flow<Boolean> = flowOf(false)
    override suspend fun setNotifiedLowMinutes(identity: String, notified: Boolean) {}
    override fun getNotifiedLowTraffic(identity: String): Flow<Boolean> = flowOf(false)
    override suspend fun setNotifiedLowTraffic(identity: String, notified: Boolean) {}
    override fun getNotifiedExhaustedMinutes(identity: String): Flow<Boolean> = flowOf(false)
    override suspend fun setNotifiedExhaustedMinutes(identity: String, notified: Boolean) {}
    override fun getNotifiedExhaustedTraffic(identity: String): Flow<Boolean> = flowOf(false)
    override suspend fun setNotifiedExhaustedTraffic(identity: String, notified: Boolean) {}
    override fun getNotifiedCriticalMinutes(identity: String): Flow<Boolean> = flowOf(false)
    override suspend fun setNotifiedCriticalMinutes(identity: String, notified: Boolean) {}
    override fun getNotifiedCriticalTraffic(identity: String): Flow<Boolean> = flowOf(false)
    override suspend fun setNotifiedCriticalTraffic(identity: String, notified: Boolean) {}
}
