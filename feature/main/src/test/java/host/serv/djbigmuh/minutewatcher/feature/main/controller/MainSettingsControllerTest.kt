package host.serv.djbigmuh.minutewatcher.feature.main.controller

import host.serv.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import host.serv.djbigmuh.minutewatcher.core.common.util.initTestLogger
import host.serv.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import host.serv.djbigmuh.minutewatcher.core.domain.MigrationFlagsRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SettingsUseCase
import host.serv.djbigmuh.minutewatcher.core.model.AppSettings
import host.serv.djbigmuh.minutewatcher.core.model.AppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class MainSettingsControllerTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val appSettingsRepository: AppSettingsRepository = mock()
    private val migrationFlagsRepository: MigrationFlagsRepository = mock()
    private val settingsUseCase = SettingsUseCase(appSettingsRepository)

    @Before
    fun setup() {
        initTestLogger()
        whenever(appSettingsRepository.observeAppSettings()).thenReturn(MutableStateFlow(AppSettings()))
        whenever(migrationFlagsRepository.isInitialSetupCompleted()).thenReturn(MutableStateFlow(true))
    }

    private fun createController(scope: CoroutineScope) =
        MainSettingsController(
            appSettingsRepository,
            migrationFlagsRepository,
            settingsUseCase,
            scope,
        )

    @Test
    fun `setTheme does not crash on IOException`() {
        runTest {
            withTimeout(5.seconds) {
                doAnswer { throw IOException() }.whenever(appSettingsRepository).updateAppSettings(any())
                val controller = createController(backgroundScope)
                controller.setTheme(AppTheme.DARK)
                advanceUntilIdle()
                verify(appSettingsRepository).updateAppSettings(any())
            }
        }
    }

    @Test
    fun `setLanguage does not crash on IOException`() {
        runTest {
            withTimeout(5.seconds) {
                doAnswer { throw IOException() }.whenever(appSettingsRepository).updateAppSettings(any())
                val controller = createController(backgroundScope)
                controller.setLanguage("en")
                advanceUntilIdle()
                verify(appSettingsRepository).updateAppSettings(any())
            }
        }
    }

    @Test
    fun `completeOnboarding does not crash on IOException`() {
        runTest {
            withTimeout(5.seconds) {
                doAnswer { throw IOException() }.whenever(appSettingsRepository).updateAppSettings(any())
                val controller = createController(backgroundScope)
                controller.completeOnboarding()
                advanceUntilIdle()
                verify(appSettingsRepository).updateAppSettings(any())
            }
        }
    }

    @Test
    fun `dismissWidgetError does not crash on IOException`() {
        runTest {
            withTimeout(5.seconds) {
                doAnswer { throw IOException() }.whenever(appSettingsRepository).updateAppSettings(any())
                val controller = createController(backgroundScope)
                controller.dismissWidgetError()
                advanceUntilIdle()
                verify(appSettingsRepository).updateAppSettings(any())
            }
        }
    }

    @Test
    fun `dismissInaccurateCalls does not crash on IOException`() {
        runTest {
            withTimeout(5.seconds) {
                doAnswer { throw IOException() }.whenever(appSettingsRepository).updateAppSettings(any())
                val controller = createController(backgroundScope)
                controller.dismissInaccurateCalls()
                advanceUntilIdle()
                verify(appSettingsRepository).updateAppSettings(any())
            }
        }
    }

    @Test
    fun `setNotificationPermissionRequested does not crash on IOException`() {
        runTest {
            withTimeout(5.seconds) {
                doAnswer { throw IOException() }.whenever(appSettingsRepository).updateAppSettings(any())
                val controller = createController(backgroundScope)
                controller.setNotificationPermissionRequested()
                advanceUntilIdle()
                verify(appSettingsRepository).updateAppSettings(any())
            }
        }
    }

    @Test
    fun `setPhonePermissionRequested does not crash on IOException`() {
        runTest {
            withTimeout(5.seconds) {
                doAnswer { throw IOException() }.whenever(appSettingsRepository).updateAppSettings(any())
                val controller = createController(backgroundScope)
                controller.setPhonePermissionRequested()
                advanceUntilIdle()
                verify(appSettingsRepository).updateAppSettings(any())
            }
        }
    }

    @Test
    fun `markUsageAccessDialogShown does not crash on IOException`() {
        runTest {
            withTimeout(5.seconds) {
                doAnswer { throw IOException() }.whenever(appSettingsRepository).updateAppSettings(any())
                val controller = createController(backgroundScope)
                controller.markUsageAccessDialogShown()
                advanceUntilIdle()
                verify(appSettingsRepository).updateAppSettings(any())
            }
        }
    }

    @Test
    fun `cancellation is not suppressed`() {
        runTest {
            withTimeout(5.seconds) {
                var wasCancelled = false
                val fakeRepository =
                    object : AppSettingsRepository {
                        override fun getAppSettings() = MutableStateFlow(AppSettings())

                        override fun observeAppSettings() = MutableStateFlow(AppSettings())

                        override suspend fun updateAppSettings(transform: (AppSettings) -> AppSettings) {
                            try {
                                delay(10_000)
                            } catch (e: CancellationException) {
                                wasCancelled = true
                                throw e
                            }
                        }
                    }
                val localSettingsUseCase = SettingsUseCase(fakeRepository)
                val appJob = Job()
                val appScope = CoroutineScope(mainDispatcherRule.testDispatcher + appJob)
                val controller =
                    MainSettingsController(
                        fakeRepository,
                        migrationFlagsRepository,
                        localSettingsUseCase,
                        appScope,
                    )

                controller.setTheme(AppTheme.DARK)
                runCurrent()

                appJob.cancel()
                advanceUntilIdle()

                assertTrue("Cancellation should be propagated to the repository", wasCancelled)
            }
        }
    }
}
