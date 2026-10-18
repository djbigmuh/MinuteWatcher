package host.serv.djbigmuh.minutewatcher.feature.widget

import host.serv.djbigmuh.minutewatcher.core.model.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetUpdaterRenderModeTest : BaseWidgetUpdaterFixture() {
    @Test
    fun `updateAll calls renderer with correct parameters`() =
        runTest {
            val updater = createUpdater(backgroundScope)
            appSettingsRepository.settingsFlow.value = AppSettings(widgetLimitExceeded = false)

            updater.updateAll()

            verify(renderer).renderAll(
                context = eq(context),
                forceFull = any(),
            )
        }

    @Test
    fun `resolveRenderMode defaults to FULL on settings timeout`() =
        runTest {
            val mockRepo: host.serv.djbigmuh.minutewatcher.core.domain.AppSettingsRepository = mock()
            whenever(mockRepo.getAppSettings()).thenReturn(emptyFlow())
            whenever(mockRepo.observeAppSettings()).thenReturn(appSettingsRepository.settingsFlow)

            val limitManager = WidgetLimitManager(mockRepo, backgroundScope)
            val updater =
                WidgetUpdaterImpl(
                    context = context,
                    appSettingsRepository = mockRepo,
                    renderer = renderer,
                    retryManager = retryManager,
                    limitManager = limitManager,
                    errorClassifier = errorClassifier,
                    scope = backgroundScope,
                )

            updater.updateAll()
            advanceUntilIdle()

            verify(renderer).renderAll(context, forceFull = true)
        }

    @Test
    fun `performSingleUpdate uses limitManager cache to avoid repository check`() =
        runTest {
            val mockLimitManager: WidgetLimitManager = mock()
            whenever(mockLimitManager.isLimitExceeded()).thenReturn(true)
            whenever(mockLimitManager.shouldProbe()).thenReturn(false)

            val updaterWithMock =
                WidgetUpdaterImpl(
                    context = context,
                    appSettingsRepository = appSettingsRepository,
                    renderer = renderer,
                    retryManager = retryManager,
                    limitManager = mockLimitManager,
                    errorClassifier = errorClassifier,
                    scope = backgroundScope,
                )

            whenever(renderer.renderAll(any(), any())).thenReturn(Unit)

            updaterWithMock.updateAll()

            verify(mockLimitManager).isLimitExceeded()
            verify(mockLimitManager).shouldProbe()
            verify(renderer).renderAll(context, forceFull = false)
        }

    @Test
    fun `updateAll uses limitManager to check for probing when limit is exceeded`() =
        runTest {
            appSettingsRepository.settingsFlow.value = AppSettings(widgetLimitExceeded = true)

            val mockLimitManager: WidgetLimitManager = mock()
            whenever(mockLimitManager.isLimitExceeded()).thenReturn(true)
            whenever(mockLimitManager.shouldProbe()).thenReturn(true)
            whenever(mockLimitManager.performProbeUpdate(any(), any())).thenAnswer {
                UpdateResult.Success(wasFull = true)
            }

            val updaterWithMock =
                WidgetUpdaterImpl(
                    context = context,
                    appSettingsRepository = appSettingsRepository,
                    renderer = renderer,
                    retryManager = retryManager,
                    limitManager = mockLimitManager,
                    errorClassifier = errorClassifier,
                    scope = backgroundScope,
                )

            updaterWithMock.updateAll()
            advanceUntilIdle()

            verify(mockLimitManager).isLimitExceeded()
            verify(mockLimitManager).shouldProbe()
            verify(mockLimitManager).performProbeUpdate(eq(context), any())
        }
}
