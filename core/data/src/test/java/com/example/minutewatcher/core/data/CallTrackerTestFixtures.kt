package com.example.minutewatcher.core.data

import android.Manifest
import android.telephony.SubscriptionInfo
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import com.example.minutewatcher.core.common.util.FixedTimeProvider
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.data.util.DataStoreRetryHelper
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.CallSessionRepository
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.model.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
abstract class CallTrackerTestFixtures {
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

    protected val testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher()
    protected val testScope = kotlinx.coroutines.test.TestScope(testDispatcher)

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

    internal fun createTracker(): CallTracker {
        val attributionResolver = createAttributionResolver()
        val startHandler = createStartHandler(attributionResolver)
        val endHandler = createEndHandler(attributionResolver)
        val sessionCleaner = createSessionCleaner(attributionResolver)
        val settingsHandler = createSettingsHandler()

        return CallTracker(
            context = context,
            callSessionRepository = callSessionRepository,
            dataChangeNotifier = dataChangeNotifier,
            permissionManager = permissionManager,
            telephonyManager = telephonyManager,
            scope = testScope,
            attributionResolver = attributionResolver,
            startHandler = startHandler,
            endHandler = endHandler,
            sessionCleaner = sessionCleaner,
            settingsHandler = settingsHandler,
            permissionStateNotifier = permissionStateNotifier,
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

    internal fun createStartHandler(attributionResolver: CallAttributionResolver) =
        CallStartHandler(
            callSessionRepository,
            timeProvider,
            retryHelper,
            attributionResolver,
        )

    internal fun createEndHandler(attributionResolver: CallAttributionResolver) =
        CallEndHandler(
            callSessionRepository,
            timeProvider,
            retryHelper,
            attributionResolver,
        )

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
