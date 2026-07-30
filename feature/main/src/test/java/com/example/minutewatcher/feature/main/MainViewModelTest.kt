package com.example.minutewatcher.feature.main

import androidx.lifecycle.SavedStateHandle
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.AutoResetUseCase
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.PermissionRepository
import com.example.minutewatcher.core.domain.SettingsUseCase
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimLimitsRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.UsageUseCase
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.AppTheme
import com.example.minutewatcher.core.model.PermissionUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
@RunWith(RobolectricTestRunner::class)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()
    private val simLimitsRepository: SimLimitsRepository = mock()
    private val appSettingsRepository: AppSettingsRepository = mock()
    private val usageStateRepository: UsageStateRepository = mock()
    private val autoResetUseCase: AutoResetUseCase = mock()
    private val usageUseCase: UsageUseCase = mock()
    private val settingsUseCase: SettingsUseCase = mock()
    private val dataChangeNotifier: DataChangeNotifier = mock()
    private val permissionRepository: PermissionRepository = mock()
    private val savedStateHandle = SavedStateHandle()

    private val testDispatcher = UnconfinedTestDispatcher()

    private val phonePermissionFlow = MutableStateFlow(false)
    private val usageStatsPermissionFlow = MutableStateFlow(false)
    private val notificationPermissionFlow = MutableStateFlow(false)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        whenever(permissionRepository.phoneStatePermissionGranted).thenReturn(phonePermissionFlow)
        whenever(permissionRepository.usageStatsPermissionGranted).thenReturn(usageStatsPermissionFlow)
        whenever(permissionRepository.notificationPermissionGranted).thenReturn(notificationPermissionFlow)

        whenever(usageStateRepository.observeTrackingStatus()).thenReturn(MutableStateFlow(emptyMap()))
        whenever(usageStateRepository.observeSimCardUiModels()).thenReturn(MutableStateFlow(emptyList()))
        whenever(usageStateRepository.observeSnapshot()).thenReturn(MutableStateFlow(mock()))
        whenever(appSettingsRepository.observeAppSettings()).thenReturn(MutableStateFlow(AppSettings()))
        whenever(settingsUseCase.appTheme).thenReturn(MutableStateFlow(AppTheme.FOLLOW_SYSTEM))
        whenever(settingsUseCase.appLanguage).thenReturn(MutableStateFlow(null))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `trackingBannerState shows phone permission banner when granted is false`() =
        runTest {
            val viewModel = createViewModel()

            phonePermissionFlow.value = false

            val bannerState = viewModel.trackingBannerState.first()
            assertTrue(bannerState.phonePermissionState != PermissionUiState.GRANTED)
        }

    @Test
    fun `trackingBannerState hides phone permission banner when granted is true`() =
        runTest {
            val viewModel = createViewModel()

            phonePermissionFlow.value = true
            usageStatsPermissionFlow.value = true

            val bannerState = viewModel.trackingBannerState.first()
            assertTrue(bannerState.phonePermissionState == PermissionUiState.GRANTED)
        }

    @Test
    fun `trackingBannerState reflects permanently denied status`() =
        runTest {
            val viewModel = createViewModel()

            viewModel.setPhonePermissionPermanentlyDenied(true)

            val bannerState = viewModel.trackingBannerState.first()
            assertTrue(bannerState.phonePermissionState == PermissionUiState.PERMANENTLY_DENIED)
        }

    @Test
    fun `trackingBannerState shows notification banner when alerts enabled but not granted`() =
        runTest {
            val settingsFlow = MutableStateFlow(AppSettings(notifyLowMinutes = true, notifyLowTraffic = false))
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(settingsFlow)
            notificationPermissionFlow.value = false

            val viewModel = createViewModel()

            val bannerState = viewModel.trackingBannerState.first()
            assertTrue(bannerState.notificationPermissionState != PermissionUiState.GRANTED)
        }

    @Test
    fun `trackingBannerState hides notification banner when granted even if alerts enabled`() =
        runTest {
            val settingsFlow = MutableStateFlow(AppSettings(notifyLowMinutes = true, notifyLowTraffic = true))
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(settingsFlow)
            notificationPermissionFlow.value = true

            val viewModel = createViewModel()

            val bannerState = viewModel.trackingBannerState.first()
            assertTrue(bannerState.notificationPermissionState == PermissionUiState.GRANTED)
        }

    @Test
    fun `trackingBannerState correctly identifies NOT_REQUESTED vs DENIED states`() =
        runTest {
            // Case 1: Not requested
            val settingsFlow = MutableStateFlow(AppSettings(phonePermissionRequested = false))
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(settingsFlow)
            phonePermissionFlow.value = false
            
            val viewModel = createViewModel()
            var bannerState = viewModel.trackingBannerState.first()
            assertTrue(bannerState.phonePermissionState == PermissionUiState.NOT_REQUESTED)

            // Case 2: Requested but denied
            settingsFlow.value = AppSettings(phonePermissionRequested = true)
            bannerState = viewModel.trackingBannerState.first()
            assertTrue(bannerState.phonePermissionState == PermissionUiState.DENIED)
        }

    @Test
    fun `trackingBannerState hides notification banner when alerts disabled`() =
        runTest {
            val settingsFlow = MutableStateFlow(AppSettings(notifyLowMinutes = false, notifyLowTraffic = false))
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(settingsFlow)
            notificationPermissionFlow.value = false

            val viewModel = createViewModel()

            val bannerState = viewModel.trackingBannerState.first()
            assertTrue(bannerState.notificationPermissionState == PermissionUiState.GRANTED)
        }

    @Test
    fun `trackingBannerState shows usage access banner when permission missing`() =
        runTest {
            val trackingFlow = MutableStateFlow(mapOf(
                "traffic" to com.example.minutewatcher.core.model.TrackingStatus.Error(
                    com.example.minutewatcher.core.model.TrackingStatus.ErrorType.PERMISSION_MISSING
                )
            ))
            whenever(usageStateRepository.observeTrackingStatus()).thenReturn(trackingFlow)
            
            val viewModel = createViewModel()

            val bannerState = viewModel.trackingBannerState.first()
            assertTrue(bannerState.showUsageAccessBanner)
        }

    @Test
    fun `trackingBannerState shows traffic error banner when other tracking error occurs`() =
        runTest {
            val trackingFlow = MutableStateFlow(mapOf(
                "traffic" to com.example.minutewatcher.core.model.TrackingStatus.Error(
                    com.example.minutewatcher.core.model.TrackingStatus.ErrorType.UNKNOWN, "Some error"
                )
            ))
            whenever(usageStateRepository.observeTrackingStatus()).thenReturn(trackingFlow)
            
            val viewModel = createViewModel()

            val bannerState = viewModel.trackingBannerState.first()
            assertTrue(bannerState.showTrafficErrorBanner)
            assertFalse(bannerState.showUsageAccessBanner)
        }

    @Test
    fun `trackingBannerState shows shortcuts error banner when shortcuts error occurs`() =
        runTest {
            val trackingFlow = MutableStateFlow(mapOf(
                "shortcuts" to com.example.minutewatcher.core.model.TrackingStatus.Error(
                    com.example.minutewatcher.core.model.TrackingStatus.ErrorType.UNKNOWN, "Some error"
                )
            ))
            whenever(usageStateRepository.observeTrackingStatus()).thenReturn(trackingFlow)
            
            val viewModel = createViewModel()

            val bannerState = viewModel.trackingBannerState.first()
            assertTrue(bannerState.showShortcutsErrorBanner)
        }

    @Test
    fun `setTheme calls settingsUseCase`() =
        runTest {
            val viewModel = createViewModel()
            val theme = AppTheme.DARK

            viewModel.setTheme(theme)

            org.mockito.kotlin.verify(settingsUseCase).setTheme(theme)
        }

    private fun createViewModel() =
        MainViewModel(
            simIdentityRepository,
            simUsageRepository,
            simLimitsRepository,
            appSettingsRepository,
            usageStateRepository,
            autoResetUseCase,
            usageUseCase,
            settingsUseCase,
            permissionRepository,
            dataChangeNotifier,
            savedStateHandle,
            testDispatcher,
        )
}
