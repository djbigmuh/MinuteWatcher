package com.example.minutewatcher.feature.main.controller

import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.RefreshResult
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SchemaVersion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class MainInitializationControllerTest {
    private val appSettingsRepository: AppSettingsRepository = mock()
    private val refreshController: MainSimRefreshController = mock()
    private val dialogController: MainDialogController = mock()
    private val autoResetScheduler: AutoResetScheduler = mock()
    private val timeProvider: TimeProvider = mock()
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var controller: MainInitializationController

    @Before
    fun setup() {
        whenever(appSettingsRepository.observeAppSettings()).thenReturn(MutableStateFlow(AppSettings()))
        whenever(
            appSettingsRepository.getAppSettings(),
        ).thenReturn(MutableStateFlow(AppSettings(onboardingCompleted = true)))

        controller =
            MainInitializationController(
                appSettingsRepository = appSettingsRepository,
                refreshController = refreshController,
                dialogController = dialogController,
                autoResetScheduler = autoResetScheduler,
                timeProvider = timeProvider,
                ioDispatcher = testDispatcher,
            )
    }

    @Test
    fun `initializeApp retries when refresh returns SkippedAlreadyInProgress`() =
        testScope.runTest {
            whenever(refreshController.refreshSimCardsInternal())
                .thenReturn(MainSimRefreshController.RefreshOutcome.SkippedAlreadyInProgress)
                .thenReturn(
                    MainSimRefreshController.RefreshOutcome.Completed(
                        RefreshResult.Success(GlobalSnapshot(SchemaVersion(1, 0, 0), 0L, emptyList(), emptyList())),
                    ),
                )

            controller.initializeApp(
                scope = this,
                lastRefreshAt = 0L,
                onUpdateLastRefreshAt = {},
            )

            advanceUntilIdle()

            verify(refreshController, times(2)).refreshSimCardsInternal()
            assertEquals(InitializationState.Completed, controller.initializationState.value)
        }

    @Test
    fun `initializeApp fails if refresh always returns SkippedAlreadyInProgress`() =
        testScope.runTest {
            whenever(refreshController.refreshSimCardsInternal())
                .thenReturn(MainSimRefreshController.RefreshOutcome.SkippedAlreadyInProgress)

            controller.initializeApp(
                scope = this,
                lastRefreshAt = 0L,
                onUpdateLastRefreshAt = {},
            )

            advanceUntilIdle()

            verify(refreshController, times(3)).refreshSimCardsInternal()
            assertEquals(InitializationState.Failed, controller.initializationState.value)
        }
}
