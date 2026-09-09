package com.example.minutewatcher.core.data

import android.Manifest
import android.telephony.SubscriptionInfo
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import com.example.minutewatcher.core.android.testing.SingletonResetRule
import com.example.minutewatcher.core.common.util.FixedTimeProvider
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.data.util.DataStoreRetryHelper
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.CallSessionRepository
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.model.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
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

    protected val context: android.content.Context = ApplicationProvider.getApplicationContext()
    protected val simIdentityRepository: SimIdentityRepository = mock()
    protected val simUsageRepository: SimUsageRepository = mock()
    protected val appSettingsRepository: AppSettingsRepository = mock()
    protected val callSessionRepository: CallSessionRepository = mock()
    protected val dataChangeNotifier: DataChangeNotifier = mock()
    protected val permissionManager: PermissionManager = mock()
    protected val telephonyManager: TelephonyManager = mock()
    protected val permissionStateNotifier: com.example.minutewatcher.core.domain.PermissionStateNotifier = mock()
    protected val permissionChangesFlow = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(replay = 1)
    protected val retryHelper = DataStoreRetryHelper()

    protected val timeProvider =
        FixedTimeProvider(
            currentTime = 500000000L,
            currentRealtime = 500000000L,
            currentUptime = 500000000L,
        )

    protected open val testDispatcher = StandardTestDispatcher()
    protected val testScope by lazy { TestScope(testDispatcher) }

    @Before
    open fun setup() {
        val application = context as android.app.Application
        org.robolectric.Shadows
            .shadowOf(application)
            .grantPermissions(Manifest.permission.READ_PHONE_STATE)

        whenever(appSettingsRepository.getAppSettings()).thenReturn(flowOf(AppSettings()))
        whenever(permissionStateNotifier.changes).thenReturn(permissionChangesFlow)
        whenever(callSessionRepository.getCallIdentity(any())).thenReturn(flowOf(null))
        whenever(callSessionRepository.getCallStartTime(any())).thenReturn(flowOf(0L))
        whenever(callSessionRepository.getBootTime(any())).thenReturn(flowOf(0L))
        whenever(callSessionRepository.getUptime(any())).thenReturn(flowOf(0L))

        whenever(simIdentityRepository.getSimOperator(any())).thenReturn(flowOf("MTS"))
        @Suppress("DEPRECATION")
        whenever(telephonyManager.createForSubscriptionId(any())).thenReturn(telephonyManager)
        whenever(telephonyManager.callStateForSubscription).thenReturn(TelephonyManager.CALL_STATE_OFFHOOK)
        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
        whenever(permissionManager.isTelephonySupported()).thenReturn(true)
    }

    internal fun createTracker(scope: CoroutineScope = testScope): CallTracker {
        val attributionResolver = createAttributionResolver()
        val mutexHolder = createMutexHolder()
        val settingsHandler = createSettingsHandler()
        val sessionCleaner = createSessionCleaner(attributionResolver)
        val endResolver = createEndResolver()

        val startHandler =
            CallStartHandler(
                callSessionRepository,
                timeProvider,
                retryHelper,
                attributionResolver,
                mutexHolder,
                sessionCleaner,
                settingsHandler,
            )
        val endHandler =
            CallEndHandler(
                callSessionRepository,
                timeProvider,
                retryHelper,
                attributionResolver,
                mutexHolder,
                sessionCleaner,
                settingsHandler,
                endResolver,
                dataChangeNotifier,
            )

        return CallTracker(
            callSessionRepository = callSessionRepository,
            permissionManager = permissionManager,
            scope = scope,
            attributionResolver = attributionResolver,
            startHandler = startHandler,
            endHandler = endHandler,
            sessionCleaner = sessionCleaner,
            permissionStateNotifier = permissionStateNotifier,
            callMutexHolder = mutexHolder,
        )
    }

    internal fun createAttributionResolver() =
        CallAttributionResolver(
            simIdentityRepository,
            callSessionRepository,
            permissionManager,
            retryHelper,
            dataChangeNotifier,
        )

    internal fun createStartHandler(
        attributionResolver: CallAttributionResolver,
        mutexHolder: CallMutexHolder = createMutexHolder(),
    ) = CallStartHandler(
        callSessionRepository,
        timeProvider,
        retryHelper,
        attributionResolver,
        mutexHolder,
        createSessionCleaner(attributionResolver),
        createSettingsHandler(),
    )

    internal fun createEndHandler(
        attributionResolver: CallAttributionResolver,
        mutexHolder: CallMutexHolder = createMutexHolder(),
    ) = CallEndHandler(
        callSessionRepository,
        timeProvider,
        retryHelper,
        attributionResolver,
        mutexHolder,
        createSessionCleaner(attributionResolver),
        createSettingsHandler(),
        createEndResolver(),
        dataChangeNotifier,
    )

    internal fun createMutexHolder() = CallMutexHolder()

    internal fun createSessionCleaner(attributionResolver: CallAttributionResolver) =
        CallSessionCleaner(
            callSessionRepository,
            attributionResolver,
        )

    internal fun createSettingsHandler() =
        CallTrackingSettingsHandler(
            appSettingsRepository,
            simUsageRepository,
            dataChangeNotifier,
            retryHelper,
        )

    internal fun createEndResolver() =
        CallEndResolver(
            context = context,
            callSessionRepository = callSessionRepository,
            permissionManager = permissionManager,
            telephonyManager = telephonyManager,
        )

    protected suspend fun setupCommonMocks() {
        whenever(simIdentityRepository.getIdentity(0)).thenReturn("id_0")
        whenever(simIdentityRepository.getIdentity(1)).thenReturn("id_1")

        val mockIdentity =
            com.example.minutewatcher.core.model.SimIdentity(
                stableId = "id_1",
                subscriptionId = 1,
                slotIndex = 0,
                source = com.example.minutewatcher.core.model.IdentitySource.ICCID,
                isStable = true,
            )
        val mockSim =
            com.example.minutewatcher.core.model.ActiveSimInfo(
                identity = mockIdentity,
                slotIndex = 0,
                subscriptionId = 1,
                iccId = "id_1",
                displayName = "SIM 1",
                carrierName = "Carrier",
                mccMnc = "12345",
                isDefaultData = true,
                isDefaultVoice = true,
            )

        whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(listOf(mockSim)))
        whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(emptyList())

        val mockSubInfo = mock<SubscriptionInfo>()
        whenever(mockSubInfo.subscriptionId).thenReturn(1)
        whenever(mockSubInfo.simSlotIndex).thenReturn(0)
        whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(mockSubInfo))
    }
}
