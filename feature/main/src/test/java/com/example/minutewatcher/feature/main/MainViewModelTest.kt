package com.example.minutewatcher.feature.main

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.example.minutewatcher.core.data.ResetManager
import com.example.minutewatcher.core.data.UpdateCoordinator
import com.example.minutewatcher.core.domain.PermissionRepository
import com.example.minutewatcher.core.domain.SettingsRepository
import com.example.minutewatcher.core.domain.SettingsUseCase
import com.example.minutewatcher.core.domain.SimRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.UsageUseCase
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.TrackingStatus
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
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private val context: Context = mock()
    private val simRepository: SimRepository = mock()
    private val settingsRepository: SettingsRepository = mock()
    private val usageStateRepository: UsageStateRepository = mock()
    private val resetManager: ResetManager = mock()
    private val usageUseCase: UsageUseCase = mock()
    private val settingsUseCase: SettingsUseCase = mock()
    private val updateCoordinator: UpdateCoordinator = mock()
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
        whenever(settingsRepository.observeAppSettings()).thenReturn(MutableStateFlow(AppSettings()))
        whenever(settingsUseCase.appTheme).thenReturn(MutableStateFlow(mock()))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `trackingBannerState shows phone permission banner when granted is false`() = runTest {
        val viewModel = createViewModel()
        
        phonePermissionFlow.value = false
        
        val bannerState = viewModel.trackingBannerState.first()
        assertTrue(bannerState.showPhonePermissionBanner)
    }

    @Test
    fun `trackingBannerState hides phone permission banner when granted is true`() = runTest {
        val viewModel = createViewModel()
        
        phonePermissionFlow.value = true
        usageStatsPermissionFlow.value = true
        
        val bannerState = viewModel.trackingBannerState.first()
        assertFalse(bannerState.showPhonePermissionBanner)
    }

    @Test
    fun `trackingBannerState reflects permanently denied status`() = runTest {
        val viewModel = createViewModel()
        
        viewModel.setPhonePermissionPermanentlyDenied(true)
        
        val bannerState = viewModel.trackingBannerState.first()
        assertTrue(bannerState.isPhonePermissionPermanentlyDenied)
    }

    private fun createViewModel() = MainViewModel(
        context,
        simRepository,
        settingsRepository,
        usageStateRepository,
        resetManager,
        usageUseCase,
        settingsUseCase,
        permissionRepository,
        updateCoordinator,
        savedStateHandle
    )
}
