package com.example.minutewatcher.feature.settings.ui

import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.WidgetSettingsLoader
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.WidgetSettingsState
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

    protected lateinit var viewModel: WidgetSettingsViewModel

    @Before
    open fun setup() {
        val snapshot = GlobalSnapshot(SchemaVersion(1, 0, 0), 0L, emptyList(), emptyList())
        val snapshotFlow = MutableStateFlow(snapshot)
        whenever(usageStateRepository.observeSnapshot()).thenReturn(snapshotFlow)
        whenever(appSettingsRepository.observeAppSettings()).thenReturn(MutableStateFlow(AppSettings()))

        runBlocking {
            whenever(loader.load()).thenReturn(WidgetSettingsState())
            whenever(saver.save(any(), any())).thenReturn(SaveResult.Success)
        }
    }

    protected fun TestScope.initViewModel() {
        viewModel = WidgetSettingsViewModel(loader, saver, appSettingsRepository, backgroundScope, usageStateRepository)
        advanceUntilIdle()
    }
}
