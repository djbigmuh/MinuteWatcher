package com.github.djbigmuh.minutewatcher.feature.settings.ui

import com.github.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import com.github.djbigmuh.minutewatcher.core.domain.AodSummaryAvailability
import com.github.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import com.github.djbigmuh.minutewatcher.core.domain.UsageStateRepository
import com.github.djbigmuh.minutewatcher.core.domain.WidgetSettingsLoader
import com.github.djbigmuh.minutewatcher.core.model.AppSettings
import com.github.djbigmuh.minutewatcher.core.model.GlobalSnapshot
import com.github.djbigmuh.minutewatcher.core.model.SchemaVersion
import com.github.djbigmuh.minutewatcher.core.model.WidgetSettingsState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Rule
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
abstract class BaseWidgetSettingsViewModelFixture {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    protected val loader: WidgetSettingsLoader = mock()
    protected val saver: WidgetSettingsSaver = mock()
    protected val usageStateRepository: UsageStateRepository = mock()
    protected val appSettingsRepository: AppSettingsRepository = mock()
    protected val aodSummaryAvailability: AodSummaryAvailability = mock()

    protected lateinit var viewModel: WidgetSettingsViewModel
    private val viewModelStore = androidx.lifecycle.ViewModelStore()

    protected fun clearViewModel() {
        viewModelStore.clear()
    }

    @Before
    @com.github.djbigmuh.minutewatcher.core.common.annotation.RunBlockingBridge("Test fixture setup bridge")
    open fun setup() {
        val snapshot = GlobalSnapshot(SchemaVersion(1, 0, 0), 0L, emptyList(), emptyList())
        val snapshotFlow = MutableStateFlow(snapshot)
        whenever(usageStateRepository.observeSnapshot()).thenReturn(snapshotFlow)
        whenever(appSettingsRepository.observeAppSettings()).thenReturn(MutableStateFlow(AppSettings()))

        runBlocking {
            whenever(loader.load()).thenReturn(WidgetSettingsState())
            whenever(saver.save(any(), any())).thenReturn(SaveResult.Success)
            whenever(aodSummaryAvailability.isAodSummaryAvailable()).thenReturn(true)
        }
    }

    protected fun TestScope.initViewModel() {
        val factory =
            object : androidx.lifecycle.ViewModelProvider.Factory {
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                    WidgetSettingsViewModel(
                        loader = loader,
                        saver = saver,
                        appSettingsRepository = appSettingsRepository,
                        appScope = backgroundScope,
                        usageStateRepository = usageStateRepository,
                        aodSummaryAvailability = aodSummaryAvailability,
                    ) as T
            }
        viewModel =
            androidx.lifecycle.ViewModelProvider(
                viewModelStore,
                factory,
            )[WidgetSettingsViewModel::class.java]
        advanceUntilIdle()
    }
}
