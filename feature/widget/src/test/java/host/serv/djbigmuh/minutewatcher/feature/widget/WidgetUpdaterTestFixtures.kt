package host.serv.djbigmuh.minutewatcher.feature.widget

import android.content.Context
import host.serv.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import host.serv.djbigmuh.minutewatcher.core.common.util.initTestLogger
import host.serv.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import host.serv.djbigmuh.minutewatcher.core.domain.TrackingStatusProvider
import host.serv.djbigmuh.minutewatcher.core.model.AppSettings
import host.serv.djbigmuh.minutewatcher.core.model.TrackingKey
import host.serv.djbigmuh.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Before
import org.junit.Rule
import org.mockito.kotlin.mock

class TestCrashException(
    message: String,
) : RuntimeException(message)

class FakeAppSettingsRepository(
    initialSettings: AppSettings = AppSettings(),
) : AppSettingsRepository,
    TrackingStatusProvider {
    val settingsFlow = MutableStateFlow(initialSettings)

    override val key: TrackingKey = TrackingKey.APP_SETTINGS

    override val status: StateFlow<TrackingStatus> = MutableStateFlow(TrackingStatus.Active)

    override fun observeAppSettings(): StateFlow<AppSettings> = settingsFlow

    override fun getAppSettings(): Flow<AppSettings> = settingsFlow

    override suspend fun updateAppSettings(transform: (AppSettings) -> AppSettings) {
        settingsFlow.value = transform(settingsFlow.value)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
abstract class BaseWidgetUpdaterFixture {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    protected lateinit var context: Context
    protected val appSettingsRepository = FakeAppSettingsRepository()
    protected val renderer: WidgetRenderer = mock()
    protected val retryManager = WidgetUpdateRetryManager()
    protected val errorClassifier = WidgetErrorClassifier()

    @Before
    open fun setup() {
        initTestLogger()
        context = mock()
    }

    protected fun createUpdater(
        scope: CoroutineScope,
        customRenderer: WidgetRenderer = renderer,
    ): WidgetUpdaterImpl {
        val limitManager = WidgetLimitManager(appSettingsRepository, scope)
        return WidgetUpdaterImpl(
            context = context,
            appSettingsRepository = appSettingsRepository,
            renderer = customRenderer,
            retryManager = retryManager,
            limitManager = limitManager,
            errorClassifier = errorClassifier,
            scope = scope,
        )
    }
}
