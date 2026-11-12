package host.serv.djbigmuh.minutewatcher.core.data

import android.app.Application
import android.telephony.TelephonyManager
import host.serv.djbigmuh.minutewatcher.core.android.testing.SingletonResetRule
import host.serv.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import host.serv.djbigmuh.minutewatcher.core.common.util.FixedTimeProvider
import host.serv.djbigmuh.minutewatcher.core.common.util.PermissionManager
import host.serv.djbigmuh.minutewatcher.core.common.util.TimeProvider
import host.serv.djbigmuh.minutewatcher.core.common.util.initTestLogger
import host.serv.djbigmuh.minutewatcher.core.data.util.DataStoreRetryHelper
import host.serv.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import host.serv.djbigmuh.minutewatcher.core.domain.CallSessionRepository
import host.serv.djbigmuh.minutewatcher.core.domain.DataChangeNotifier
import host.serv.djbigmuh.minutewatcher.core.domain.PermissionStateNotifier
import host.serv.djbigmuh.minutewatcher.core.domain.SimIdentityRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimUsageRepository
import host.serv.djbigmuh.minutewatcher.core.model.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Before
import org.junit.Rule
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
abstract class CallTrackerTestFixtures {
    @get:Rule
    val singletonResetRule = SingletonResetRule()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    protected val context: Application = mock()
    protected val simIdentityRepository: SimIdentityRepository = mock()
    protected val simUsageRepository: SimUsageRepository = mock()
    protected val appSettingsRepository: AppSettingsRepository = mock()
    protected val callSessionRepository: CallSessionRepository = mock()
    protected val dataChangeNotifier: DataChangeNotifier = mock()
    protected val permissionManager: PermissionManager = mock()
    protected val telephonyManager: TelephonyManager = mock()
    protected val permissionStateNotifier: PermissionStateNotifier = mock()
    protected val permissionChangesFlow = MutableSharedFlow<Unit>(replay = 1)
    protected val retryHelper = DataStoreRetryHelper()

    protected val timeProvider =
        FixedTimeProvider(
            currentTime = 500000000L,
            currentRealtime = 500000000L,
            currentUptime = 500000000L,
        )

    protected open val testDispatcher: TestDispatcher get() = mainDispatcherRule.testDispatcher
    protected val testScope by lazy { TestScope(testDispatcher) }

    @Before
    open fun setup() {
        initTestLogger()
    }

    protected suspend fun setupCommonMocks() {
        whenever(appSettingsRepository.getAppSettings()).thenReturn(flowOf(AppSettings()))
        whenever(permissionStateNotifier.changes).thenReturn(permissionChangesFlow)
        whenever(callSessionRepository.getCallIdentity(any())).thenReturn(flowOf(null))
        whenever(callSessionRepository.getCallStartTime(any())).thenReturn(flowOf(0L))
        whenever(callSessionRepository.getElapsedRealtime(any())).thenReturn(flowOf(0L))
        whenever(callSessionRepository.getUptime(any())).thenReturn(flowOf(0L))
        whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(emptyList())

        whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(emptyList()))
        whenever(simUsageRepository.incrementMinutesUsedForCall(any(), any(), any())).thenReturn(true)

        whenever(telephonyManager.createForSubscriptionId(any())).thenReturn(telephonyManager)
        whenever(telephonyManager.callStateForSubscription).thenReturn(TelephonyManager.CALL_STATE_OFFHOOK)
        // @Suppress("DEPRECATION") is required because TelephonyManager.getCallState() is deprecated in API 31+.
        @Suppress("DEPRECATION")
        whenever(telephonyManager.callState).thenReturn(TelephonyManager.CALL_STATE_OFFHOOK)
        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
        whenever(permissionManager.isTelephonySupported()).thenReturn(true)
    }

    internal fun createTracker(
        scope: CoroutineScope = testScope,
        repo: CallSessionRepository = callSessionRepository,
        attributionResolver: CallAttributionResolver = createAttributionResolver(),
    ): CallTracker {
        val sessionCleaner = createSessionCleaner(attributionResolver, repo)
        val settingsHandler = createSettingsHandler()
        val mutexHolder = createMutexHolder()

        val startHandler =
            createStartHandler(
                attributionResolver,
                mutexHolder,
                sessionCleaner,
                settingsHandler,
                repo,
            )

        val endHandler =
            createEndHandler(
                attributionResolver,
                mutexHolder,
                sessionCleaner,
                settingsHandler,
                repo,
            )

        return CallTracker(
            repo,
            permissionManager,
            scope,
            attributionResolver,
            startHandler,
            endHandler,
            sessionCleaner,
            permissionStateNotifier,
            mutexHolder,
        )
    }

    internal fun createAttributionResolver() =
        CallAttributionResolver(
            simIdentityRepository,
            permissionManager,
            dataChangeNotifier,
        )

    internal fun createSessionCleaner(
        attributionResolver: CallAttributionResolver,
        repo: CallSessionRepository = callSessionRepository,
        timeProvider: TimeProvider = this.timeProvider,
    ) = CallSessionCleaner(repo, attributionResolver, timeProvider)

    internal fun createSettingsHandler() =
        CallTrackingSettingsHandler(
            appSettingsRepository,
            simUsageRepository,
            dataChangeNotifier,
            retryHelper,
        )

    internal fun createStartHandler(
        attributionResolver: CallAttributionResolver,
        mutexHolder: CallMutexHolder,
        sessionCleaner: CallSessionCleaner,
        settingsHandler: CallTrackingSettingsHandler,
        repo: CallSessionRepository = callSessionRepository,
    ) = CallStartHandler(
        repo,
        timeProvider,
        retryHelper,
        attributionResolver,
        mutexHolder,
        sessionCleaner,
        settingsHandler,
    )

    internal fun createEndHandler(
        attributionResolver: CallAttributionResolver,
        mutexHolder: CallMutexHolder,
        sessionCleaner: CallSessionCleaner,
        settingsHandler: CallTrackingSettingsHandler,
        repo: CallSessionRepository = callSessionRepository,
    ) = CallEndHandler(
        repo,
        timeProvider,
        retryHelper,
        attributionResolver,
        mutexHolder,
        sessionCleaner,
        settingsHandler,
        CallEndResolver(
            repo,
            permissionManager,
            telephonyManager,
        ),
        dataChangeNotifier,
    )

    internal fun createMutexHolder() = CallMutexHolder()
}
